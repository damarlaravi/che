/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.editor.orion.client;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import org.eclipse.che.api.project.shared.dto.EditorChangesDto;
import org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type;
import org.eclipse.che.ide.api.editor.EditorOpenedEvent;
import org.eclipse.che.ide.api.editor.EditorOpenedEventHandler;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.document.DocumentHandle;
import org.eclipse.che.ide.api.editor.events.DocumentChangeEvent;
import org.eclipse.che.ide.api.editor.reconciler.DirtyRegion;
import org.eclipse.che.ide.api.editor.reconciler.DirtyRegionQueue;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.event.ActivePartChangedEvent;
import org.eclipse.che.ide.api.event.ActivePartChangedHandler;
import org.eclipse.che.ide.api.event.EditorSettingsChangedEvent;
import org.eclipse.che.ide.api.event.EditorSettingsChangedEvent.EditorSettingsChangedHandler;
import org.eclipse.che.ide.api.event.ng.FileTrackingEvent;
import org.eclipse.che.ide.api.parts.PartPresenter;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.editor.preferences.EditorPreferencesManager;
import org.eclipse.che.ide.jsonrpc.RequestTransmitter;
import org.eclipse.che.ide.util.loging.Log;

import java.util.HashSet;

import static org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type.INSERT;
import static org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type.REMOVE;
import static org.eclipse.che.api.project.shared.dto.EditorChangesDto.Type.REPLACE_ALL;
import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.ACTIVATED;
import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.DEACTIVATED;
import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.RESUMING;
import static org.eclipse.che.ide.editor.orion.client.AutoSaveMode.Mode.SUSPENDED;
import static org.eclipse.che.ide.editor.preferences.editorproperties.EditorProperties.ENABLE_AUTO_SAVE;

/**
 * Default implementation of {@link AutoSaveMode} which provides autosave function.
 *
 * @author Roman Nikitenko
 */
public class AutoSaveModeImpl implements AutoSaveMode, EditorSettingsChangedHandler, ActivePartChangedHandler, EditorOpenedEventHandler {

    private static final int    DELAY            = 1000;
    private static final String ENDPOINT_ID      = "ws-agent";
    private static final String OUTCOMING_METHOD = "track:editor-changes";

    private EventBus                 eventBus;
    private EditorPreferencesManager editorPreferencesManager;
    private DtoFactory               dtoFactory;
    private RequestTransmitter       requestTransmitter;
    private DocumentHandle           documentHandle;
    private TextEditor               editor;
    private EditorPartPresenter      activeEditor;
    private DirtyRegionQueue         dirtyRegionQueue;
    private Mode                     mode;

    private HashSet<HandlerRegistration> handlerRegistrations = new HashSet<>(5);

    private final Timer saveTimer = new Timer() {
        @Override
        public void run() {
            save();
        }
    };


    @Inject
    public AutoSaveModeImpl(EventBus eventBus,
                            EditorPreferencesManager editorPreferencesManager,
                            DtoFactory dtoFactory,
                            RequestTransmitter requestTransmitter) {
        this.eventBus = eventBus;
        this.editorPreferencesManager = editorPreferencesManager;
        this.dtoFactory = dtoFactory;
        this.requestTransmitter = requestTransmitter;

        mode = ACTIVATED; //autosave is activated by default

        addHandlers();
    }

    @Override
    public DocumentHandle getDocumentHandle() {
        return this.documentHandle;
    }

    @Override
    public void setDocumentHandle(final DocumentHandle handle) {
        this.documentHandle = handle;
    }

    @Override
    public void install(TextEditor editor) {
        this.editor = editor;
        this.dirtyRegionQueue = new DirtyRegionQueue();
        updateAutoSaveState();
    }

    @Override
    public void uninstall() {
//        Log.error(getClass(), "++++++++++++++++++++++++++++++++++++++++++++ uninstall ");
        saveTimer.cancel();
        handlerRegistrations.forEach(HandlerRegistration::removeHandler);
    }

    @Override
    public void activate() {
        Boolean autoSaveValue = editorPreferencesManager.getBooleanValueFor(ENABLE_AUTO_SAVE);
        if (autoSaveValue != null && !autoSaveValue) {
            return;
        }

        mode = ACTIVATED;

        saveTimer.cancel();
        saveTimer.schedule(DELAY);
    }

    @Override
    public void deactivate() {
        mode = DEACTIVATED;
        saveTimer.cancel();
    }

    @Override
    public boolean isActivated() {
        return mode == ACTIVATED;
    }

    @Override
    public void onEditorSettingsChanged(EditorSettingsChangedEvent event) {
        updateAutoSaveState();
    }

    private void updateAutoSaveState() {
        Boolean autoSaveValue = editorPreferencesManager.getBooleanValueFor(ENABLE_AUTO_SAVE);
        if (autoSaveValue == null) {
            return;
        }

        if (DEACTIVATED != mode && !autoSaveValue) {
            deactivate();
        } else if (ACTIVATED != mode && autoSaveValue) {
            activate();
        }
    }

    @Override
    public void onActivePartChanged(ActivePartChangedEvent event) {
        PartPresenter activePart = event.getActivePart();
        if (!(activePart instanceof EditorPartPresenter)) {
            return;
        }
//        Log.error(getClass(), " onActivePartChanged active editor = " + ((EditorPartPresenter)activePart).getEditorInput().getFile().getLocation().toString());
        activeEditor = (EditorPartPresenter)activePart;
    }

    @Override
    public void onEditorOpened(EditorOpenedEvent editorOpenedEvent) {
        if (documentHandle != null && editor == editorOpenedEvent.getEditor()) {
//            Log.error(getClass(), "************** subscribe to events ");
            HandlerRegistration documentChangeHandlerRegistration = documentHandle.getDocEventBus().addHandler(DocumentChangeEvent.TYPE, this);
            handlerRegistrations.add(documentChangeHandlerRegistration);

            HandlerRegistration fileTrackingHandlerRegistration = eventBus.addHandler(FileTrackingEvent.TYPE, this::onFileTrackingEvent);
            handlerRegistrations.add(fileTrackingHandlerRegistration);
        }
    }

    private void onFileTrackingEvent(FileTrackingEvent event) {
        switch (event.getType()) {
            case SUSPEND: {
//                Log.error(getClass(), "--- onFileTrackingEvent  SUSPENDED " + this.hashCode());
                mode = SUSPENDED;
                saveTimer.cancel();
                break;
            }

            case RESUME: {
//                Log.error(getClass(), "--- onFileTrackingEvent  RESUME " + this.hashCode());
                mode = RESUMING;
                break;
            }

            default: {
                break;
            }
        }
    }

    @Override
    public void onDocumentChange(final DocumentChangeEvent event) {
        Log.error(getClass(), "//////////////////////////////////////////////////////////////// onDocumentChange " + mode + " /// " + editor.getEditorInput().getFile().getLocation());
//        Log.error(getClass(), "//////////////////////////////////////////////////////////////// onDocumentChange " + event.getText());
        if (documentHandle == null || !documentHandle.isSameAs(event.getDocument())) {
            Log.error(getClass(), "onDocumentChange RETURN ");
            return;
        }

        if (mode == SUSPENDED) {
//            Log.error(getClass(), "//////////////////////////////////////////////////////////////// onDocumentChange  mode == SUSPENDED");
            saveTimer.cancel();
            saveTimer.schedule(DELAY);
            return;
        }

//        if (editor != activeEditor) {
////            Log.error(getClass(), "//////////////////////////////////////////////////////////////// onDocumentChange  editor != activeEditor");
//            return;
//        }

        createDirtyRegion(event);

        saveTimer.cancel();
        saveTimer.schedule(DELAY);
    }

    private void updateFileContent(String content, VirtualFile file, Project project) {
        String projectPath = project.getPath();

        EditorChangesDto changes = dtoFactory.createDto(EditorChangesDto.class)
                                             .withType(REPLACE_ALL)
                                             .withProjectPath(projectPath)
                                             .withFileLocation(file.getLocation().toString())
                                             .withText(content);
//        Log.error(getClass(), "====///////////////////////////  before transmit REPLACE_ALL " + file.getLocation().toString());
        requestTransmitter.transmitOneToNone(ENDPOINT_ID, OUTCOMING_METHOD, changes);
    }

    /**
     * Creates a dirty region for a document event and adds it to the queue.
     *
     * @param event
     *         the document event for which to create a dirty region
     */
    private void createDirtyRegion(final DocumentChangeEvent event) {
        if (event.getRemoveCharCount() == 0 && event.getText() != null && !event.getText().isEmpty()) {
            // Insert
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getLength(),
                                                            DirtyRegion.INSERT,
                                                            event.getText()));

        } else if (event.getText() == null || event.getText().isEmpty()) {
            // Remove
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getRemoveCharCount(),
                                                            DirtyRegion.REMOVE,
                                                            null));

        } else {
            // Replace (Remove + Insert)
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getRemoveCharCount(),
                                                            DirtyRegion.REMOVE,
                                                            null));
            dirtyRegionQueue.addDirtyRegion(new DirtyRegion(event.getOffset(),
                                                            event.getLength(),
                                                            DirtyRegion.INSERT,
                                                            event.getText()));
        }
    }

    private void save() {
        if (SUSPENDED == mode) {
            return;
        }




        Project project = getProject();
        if (mode == RESUMING && project != null) {
            dirtyRegionQueue.purgeQueue();

            VirtualFile file = editor.getEditorInput().getFile();
            String content = editor.getDocument().getContents();
            Log.error(getClass(), "=============================================================== " + content);

            updateFileContent(content, file, project);
            updateAutoSaveState();
            return;
        }


        if (ACTIVATED == mode && editor.isDirty()) {
            editor.doSave();
        }

        while (dirtyRegionQueue.getSize() > 0) {
            DirtyRegion region = dirtyRegionQueue.removeNextDirtyRegion();
            transmit(region);
        }
    }

    private void transmit(final DirtyRegion dirtyRegion) {
//        Project project = getProject();
//        if (project == null) {
//            return;
//        }
//
//        VirtualFile file = editor.getEditorInput().getFile();
//        String filePath = file.getLocation().toString();
//        String projectPath = project.getPath();
//        Type type = dirtyRegion.getType().equals(DirtyRegion.INSERT) ? INSERT : REMOVE;
//
//        final EditorChangesDto changes = dtoFactory.createDto(EditorChangesDto.class)
//                                                   .withType(type)
//                                                   .withWorkingCopyOwnerID("")
//                                                   .withProjectPath(projectPath)
//                                                   .withFileLocation(filePath)
//                                                   .withOffset(dirtyRegion.getOffset())
//                                                   .withText(dirtyRegion.getText());
//
//        int length = dirtyRegion.getLength();
//        if (DirtyRegion.REMOVE.equals(dirtyRegion.getType())) {
//            changes.withRemovedCharCount(length);
//        } else {
//            changes.withLength(length);
//        }
//
//        Log.error(getClass(), "====///////////////////////////  before transmit dirty region " + filePath);
//        requestTransmitter.transmitOneToNone(ENDPOINT_ID, OUTCOMING_METHOD, changes);
    }

    private Project getProject() {
        VirtualFile file = editor.getEditorInput().getFile();
        Log.error(getClass(), "--- get project "+ file);
        if (file != null) {
            Log.error(getClass(), "--- get project  file NOT Null");
        }

        if (file instanceof Resource) {
            Log.error(getClass(), "--- get project  file instanceof Resource ");
        }



        if (file == null || !(file instanceof Resource)) {
            return null;
        }

        Project project = ((Resource)file).getProject();
        if (project == null) {
            Log.error(getClass(), "--- project NULL "+ project);
        } else {
            Log.error(getClass(), "--- project NOT NULL ");
        }

        if (project != null && project.exists()) {
//            Log.error(getClass(), "--- project exists " + project.exists());
            return project;
        }

//        Log.error(getClass(), "--- project NOT exists " + project);

        return null;
    }

    private void addHandlers() {
        HandlerRegistration activePartChangedHandlerRegistration = eventBus.addHandler(ActivePartChangedEvent.TYPE, this);
        handlerRegistrations.add(activePartChangedHandlerRegistration);

        HandlerRegistration editorSettingsChangedHandlerRegistration = eventBus.addHandler(EditorSettingsChangedEvent.TYPE, this);
        handlerRegistrations.add(editorSettingsChangedHandlerRegistration);

        HandlerRegistration editorOpenedHandlerRegistration = eventBus.addHandler(EditorOpenedEvent.TYPE, this);
        handlerRegistrations.add(editorOpenedHandlerRegistration);
    }
}
