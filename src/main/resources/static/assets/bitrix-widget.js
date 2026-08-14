const form = document.getElementById('bitrix-offer-form');
const contextToken = document.getElementById('context-token').value;
const sourceModeInput = document.getElementById('source-mode');
const sourcePicker = document.getElementById('source-picker');
const linkSourceSection = document.getElementById('link-source-section');
const recordSourceSection = document.getElementById('record-source-section');
const fileSourceSection = document.getElementById('file-source-section');
const offerFields = document.getElementById('offer-fields');
const recordingUrlInput = document.getElementById('recording-url');
const submitButton = document.getElementById('submit-button');
const formError = document.getElementById('form-error');
const processing = document.getElementById('processing');
const processingStatus = document.getElementById('processing-status');
const processingPercent = document.getElementById('processing-percent');
const progressBar = document.getElementById('progress-bar');
const deliveryStatus = document.getElementById('delivery-status');
const readyResult = document.getElementById('ready-result');
const readyMessage = document.getElementById('ready-message');
const clientMessageInput = document.getElementById('client-message');
const accompanyingTextInput = document.getElementById('accompanying-text');
const currentUserAdmin = document.getElementById('current-user-admin')?.value === 'true';
const tenantName = document.getElementById('tenant-name')?.value || '';
const openClientSettingsButton = document.getElementById('open-client-settings');
const clientSettingsModal = document.getElementById('client-settings-modal');

const cameraPreview = document.getElementById('camera-preview');
const cameraPlaceholder = document.getElementById('camera-placeholder');
const cameraStage = document.getElementById('camera-stage');
const cameraPlaceholderIcon = document.getElementById('camera-placeholder-icon');
const cameraPlaceholderTitle = document.getElementById('camera-placeholder-title');
const cameraPlaceholderText = document.getElementById('camera-placeholder-text');
const captureModePicker = document.getElementById('capture-mode-picker');
const systemAudioOption = document.getElementById('system-audio-option');
const captureSystemAudio = document.getElementById('capture-system-audio');
const captureMicrophone = document.getElementById('capture-microphone');
const cameraError = document.getElementById('camera-error');
const startCameraButton = document.getElementById('start-camera');
const switchCameraButton = document.getElementById('switch-camera');
const recordToggleButton = document.getElementById('record-toggle');
const playRecordingButton = document.getElementById('play-recording');
const playGlyph = document.getElementById('play-glyph');
const recordingBadge = document.getElementById('recording-badge');
const recordingTimer = document.getElementById('recording-timer');
const uploadProcessing = document.getElementById('upload-processing');
const uploadStatus = document.getElementById('upload-status');
const uploadProgressText = document.getElementById('upload-progress-text');
const uploadProgressBar = document.getElementById('upload-save-progress-bar');

const fileInput = document.getElementById('video-file-input');
const chooseFileButton = document.getElementById('choose-file-button');
const replaceFileButton = document.getElementById('replace-file-button');
const selectedFileCard = document.getElementById('selected-file-card');
const selectedFileName = document.getElementById('selected-file-name');
const selectedFileMeta = document.getElementById('selected-file-meta');
const fileUploadProcessing = document.getElementById('file-upload-processing');
const fileUploadStatus = document.getElementById('file-upload-status');
const fileUploadProgressText = document.getElementById('file-upload-progress-text');
const fileUploadProgressBar = document.getElementById('file-upload-progress-bar');
const filePreview = document.getElementById('file-preview');
const filePreviewFrame = document.getElementById('file-preview-frame');
const fileFullscreenButton = document.getElementById('file-fullscreen-button');
const recordFullscreenButton = document.getElementById('record-fullscreen-button');

const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 60000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90000;
const FILE_CHUNK_BYTES = 4 * 1024 * 1024;
const MAX_FILE_BYTES = 100 * 1024 * 1024;
const SCREEN_AGENT_CHANNEL = 'video-offer-screen-agent-v1';
const SCREEN_AGENT_URL = '/bitrix/screen-capture?v=028';
const SCREEN_AGENT_PICKER_OUTER_WIDTH = 612;
const SCREEN_AGENT_PICKER_OUTER_HEIGHT = 614;
const SCREEN_AGENT_INITIAL_INNER_WIDTH = 610;
const SCREEN_AGENT_INITIAL_INNER_HEIGHT = 582;

let activeOfferId = null;
let clientMessageLoadPromise = Promise.resolve();
let pollTimer = null;
let finalUploadSession = null;
let activeFileUploader = null;
let fileUploadGeneration = 0;

let captureMode = 'CAMERA';
let cameraStream = null;
let cameraDevices = [];
let activeCameraIndex = 0;
let currentCameraSegment = null;
let captureSwitching = false;

let recordingSessionActive = false;
let recordingStartedAt = null;
let timerHandle = null;
let currentSegment = null;
let nextSegmentIndex = 0;
let segmentPromises = [];
let recordedPreviewUrl = null;

let screenAgent = null;
let screenAgentId = null;
let screenAgentReady = false;
let screenAgentClosing = false;
let screenCaptureReady = false;
let screenCaptureRequestPending = false;
let screenPreviewStream = null;
let screenPreviewPeer = null;
let screenPreviewPendingCandidates = [];
let screenReadyWaiters = [];
let screenStartWaiters = new Map();
let screenStopWaiters = new Map();
let screenSegmentUploadSessions = new Map();
let screenAgentWatchdogTimer = null;
let screenAgentUnexpectedCloseHandling = false;

initializeBitrixFrame();
initializeSourcePicker();
initializeRecorder();
initializeScreenAgentMessages();
initializeFileUpload();
initializeFullscreenControls();
reportDesktopEvent('CAPABILITIES', JSON.stringify({
    mediaDevices: !!navigator.mediaDevices,
    getDisplayMediaInIframe: !!navigator.mediaDevices?.getDisplayMedia,
    screenCaptureAgent: true,
    mediaRecorder: typeof window.MediaRecorder !== 'undefined',
    fullscreenEnabled: !!document.fullscreenEnabled,
    displayCapturePolicy: featureAllowed('display-capture')
}));

initializeClientMessage();
initializeClientSettings();

form.addEventListener('submit', handleOfferSubmit);

async function handleOfferSubmit(event) {
    event.preventDefault();
    clearInterval(pollTimer);
    setError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(sourceModeInput.value === 'LINK' ? 0 : 100, 'Загрузка видео');
    fitWindow();

    try {
        await clientMessageLoadPromise;
        let response;
        if (sourceModeInput.value === 'LINK') {
            const url = recordingUrlInput.value.trim();
            if (!url) throw new Error('Вставьте ссылку на видео.');
            response = await fetch('/bitrix/video-offers', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    contextToken,
                    recordingUrl: url,
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    clientMessage: clientMessageInput.value.trim() || null,
                    viewNotificationGoal: document.getElementById('view-notification-goal').value
                })
            });
        } else {
            if (!finalUploadSession || finalUploadSession.status !== 'READY') {
                throw new Error(sourceModeInput.value === 'FILE'
                    ? 'Сначала выберите и дождитесь подготовки видеофайла.'
                    : 'Сначала запишите и дождитесь подготовки видео.');
            }
            response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(finalUploadSession.id)}/offer`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    uploadToken: finalUploadSession.uploadToken,
                    contextToken,
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    clientMessage: clientMessageInput.value.trim() || null,
                    viewNotificationGoal: document.getElementById('view-notification-goal').value
                })
            });
        }

        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось создать видеооффер');
        activeOfferId = data.id;
        renderOffer(data);
        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING' && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'READY' && deliveryFinished) {
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        } else {
            pollTimer = setInterval(checkStatus, 1000);
            await checkStatus();
        }
    } catch (error) {
        finishWithError(error.message || 'Не удалось создать видеооффер');
    }
}

function initializeSourcePicker() {
    sourcePicker.querySelectorAll('[data-source]').forEach(button => {
        button.addEventListener('click', async () => {
            const mode = button.dataset.source;
            if (!mode || mode === sourceModeInput.value || recordingSessionActive) return;
            await switchSourceMode(mode);
        });
    });
}

async function switchSourceMode(mode) {
    await resetTransientMedia();
    sourceModeInput.value = mode;
    sourcePicker.querySelectorAll('[data-source]').forEach(button => {
        const active = button.dataset.source === mode;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    clearInterval(pollTimer);
    activeOfferId = null;
    processing.hidden = true;
    readyResult.hidden = true;
    setError(null);

    linkSourceSection.hidden = mode !== 'LINK';
    recordSourceSection.hidden = mode !== 'RECORD';
    fileSourceSection.hidden = mode !== 'FILE';
    recordingUrlInput.required = mode === 'LINK';
    offerFields.hidden = mode !== 'LINK';
    submitButton.hidden = mode !== 'LINK';
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';

    if (mode === 'RECORD') {
        renderCaptureModeUi();
        if (captureMode === 'CAMERA') await ensureCameraCapture(false);
        else await ensureScreenCapture(true);
    }
    fitWindow();
}

function initializeRecorder() {
    startCameraButton.addEventListener('click', async () => {
        await ensureCameraCapture(false);
    });
    switchCameraButton.addEventListener('click', switchCamera);
    recordToggleButton.addEventListener('click', toggleRecordingSession);
    playRecordingButton.addEventListener('click', toggleRecordedPlayback);
    cameraPreview.addEventListener('play', updatePlayButtonState);
    cameraPreview.addEventListener('pause', updatePlayButtonState);
    cameraPreview.addEventListener('ended', updatePlayButtonState);

    captureModePicker.querySelectorAll('[data-capture-mode]').forEach(button => {
        button.addEventListener('click', async () => {
            const next = button.dataset.captureMode;
            if (!next || captureSwitching) return;
            if (next === captureMode) {
                if (!recordingSessionActive && next === 'SCREEN' && !screenCaptureReady) {
                    await ensureScreenCapture(true);
                }
                return;
            }
            if (recordingSessionActive) await switchRecordingSource(next);
            else await selectCaptureMode(next);
        });
    });
    captureMicrophone.addEventListener('change', handleCaptureOptionChange);
    captureSystemAudio.addEventListener('change', handleCaptureOptionChange);
    renderCaptureModeUi();
}

async function selectCaptureMode(mode) {
    captureMode = mode === 'SCREEN' ? 'SCREEN' : 'CAMERA';
    clearCameraError();
    clearRecordedPreview();
    if (captureMode === 'CAMERA') {
        closeScreenCaptureAgent();
        renderCaptureModeUi();
        await ensureCameraCapture(false);
    } else {
        stopCameraCapture();
        renderCaptureModeUi();
        renderScreenPlaceholder();
        await ensureScreenCapture(true);
    }
    fitWindow();
}

async function handleCaptureOptionChange() {
    if (recordingSessionActive) return;
    clearCameraError();
    if (captureMode === 'CAMERA') {
        await ensureCameraCapture(false);
    } else {
        closeScreenCaptureAgent();
        renderScreenPlaceholder();
        await ensureScreenCapture(true);
    }
}

function renderCaptureModeUi() {
    captureModePicker.querySelectorAll('[data-capture-mode]').forEach(button => {
        const active = button.dataset.captureMode === captureMode;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
        button.disabled = captureSwitching;
    });
    const screen = captureMode === 'SCREEN';
    systemAudioOption.hidden = !screen;
    cameraStage.classList.toggle('is-screen', screen);
    switchCameraButton.hidden = screen || cameraDevices.length < 2;
    startCameraButton.textContent = 'Включить камеру';
    startCameraButton.hidden = screen || recordingSessionActive || !!cameraStream || !!recordedPreviewUrl;
    captureMicrophone.disabled = recordingSessionActive;
    captureSystemAudio.disabled = recordingSessionActive;

    if (!recordedPreviewUrl && screen) {
        if (screenCaptureReady && screenPreviewStream) showScreenPreview();
        else renderScreenPlaceholder();
    }
}

function renderScreenPlaceholder(message) {
    if (recordedPreviewUrl) return;
    try { cameraPreview.pause(); } catch (_) { }
    cameraPreview.srcObject = null;
    cameraPreview.removeAttribute('src');
    cameraPreview.hidden = true;
    cameraPlaceholder.hidden = false;
    cameraPlaceholderIcon.textContent = '▣';
    cameraPlaceholderTitle.textContent = screenCaptureRequestPending
        ? 'Выбираем экран…'
        : (screenCaptureReady ? 'Экран выбран' : 'Экран не выбран');
    cameraPlaceholderText.textContent = message || (screenCaptureReady
        ? 'Экран готов к записи.'
        : 'Выберите экран, окно или вкладку.');
    startCameraButton.hidden = true;
    recordToggleButton.hidden = !screenCaptureReady && !recordingSessionActive;
}

function showScreenPreview() {
    if (!screenPreviewStream || recordedPreviewUrl || captureMode !== 'SCREEN') return;
    cameraPlaceholder.hidden = true;
    cameraPreview.hidden = false;
    cameraPreview.autoplay = true;
    cameraPreview.controls = false;
    cameraPreview.muted = true;
    cameraPreview.removeAttribute('src');
    cameraPreview.srcObject = screenPreviewStream;
    cameraPreview.play().catch(() => {});
    recordFullscreenButton.hidden = true;
    recordToggleButton.hidden = false;
}

async function ensureScreenCapture(fromUserGesture) {
    if (sourceModeInput.value !== 'RECORD') return false;
    if (typeof window.MediaRecorder === 'undefined') {
        setCameraError('Этот браузер не поддерживает запись экрана.');
        return false;
    }
    if (screenCaptureReady && screenAgent && !screenAgent.closed) {
        if (screenPreviewStream) showScreenPreview();
        return true;
    }
    if (screenCaptureRequestPending && screenAgent && !screenAgent.closed) {
        if (fromUserGesture) { try { screenAgent.focus(); } catch (_) { } }
        return false;
    }

    clearCameraError();
    screenCaptureRequestPending = true;
    renderScreenPlaceholder();
    fitWindow();
    try {
        await openScreenCaptureAgent(fromUserGesture);
        reportDesktopEvent('SCREEN_AGENT_OPENED', JSON.stringify({
            systemAudio: !!captureSystemAudio.checked,
            microphone: !!captureMicrophone.checked
        }));
        return screenCaptureReady;
    } catch (error) {
        screenCaptureRequestPending = false;
        setCameraError(error?.message || 'Не удалось открыть выбор экрана.');
        renderScreenPlaceholder();
        return false;
    }
}

function initializeScreenAgentMessages() {
    window.__videoOfferAcceptScreenStream = (agentId, stream) => {
        if (!agentId || agentId !== screenAgentId || !stream?.getVideoTracks?.().length) return false;
        screenPreviewStream = stream;
        if (captureMode === 'SCREEN' && !recordedPreviewUrl) showScreenPreview();
        return true;
    };

    window.addEventListener('message', event => {
        if (event.origin !== location.origin) return;
        const message = event.data || {};
        if (message.channel !== SCREEN_AGENT_CHANNEL) return;
        if (message.agentId !== screenAgentId) return;
        if (screenAgent && event.source !== screenAgent) return;
        void handleScreenAgentMessage(message);
    });

    window.addEventListener('beforeunload', () => closeScreenCaptureAgent());
}

async function handleScreenAgentMessage(message) {
    switch (message.type) {
        case 'AGENT_READY':
            screenAgentReady = true;
            screenCaptureRequestPending = true;
            sendScreenAgentMessage('INIT', {
                contextToken,
                systemAudio: !!captureSystemAudio.checked,
                microphone: !!captureMicrophone.checked
            });
            break;
        case 'INITIALIZED':
            screenAgentReady = true;
            break;
        case 'CAPTURE_READY':
            screenCaptureReady = true;
            screenCaptureRequestPending = false;
            clearCameraError();
            resolveScreenReadyWaiters();
            recordToggleButton.hidden = false;
            if (captureMode === 'SCREEN') {
                if (screenPreviewStream) showScreenPreview();
                else renderScreenPlaceholder('Экран готов к записи.');
            }
            reportDesktopEvent('SCREEN_CAPTURE_READY', JSON.stringify(message.details || {}));
            break;
        case 'CAPTURE_ERROR': {
            screenCaptureReady = false;
            screenCaptureRequestPending = false;
            const error = new Error(message.message || 'Не удалось получить экран.');
            rejectScreenReadyWaiters(error);
            setCameraError(error.message);
            renderScreenPlaceholder();
            reportDesktopEvent('SCREEN_CAPTURE_ERROR', JSON.stringify({name: message.name || '', detail: message.message || ''}));
            break;
        }
        case 'PREVIEW_OFFER':
            await acceptScreenPreviewOffer(message.description);
            break;
        case 'PREVIEW_ICE':
            await acceptScreenPreviewIce(message.candidate);
            break;
        case 'SEGMENT_UPLOAD_SESSION': {
            const index = Number(message.segmentIndex);
            const upload = message.upload || {};
            if (Number.isInteger(index) && index >= 0 && upload.id && upload.uploadToken) {
                screenSegmentUploadSessions.set(index, {
                    id: String(upload.id),
                    uploadToken: String(upload.uploadToken),
                    status: upload.status || 'RECORDING'
                });
                reportDesktopEvent('SCREEN_SEGMENT_UPLOAD_SESSION', JSON.stringify({segmentIndex: index, uploadId: upload.id}));
            }
            break;
        }
        case 'SEGMENT_STARTED': {
            const index = Number(message.segmentIndex);
            const waiter = screenStartWaiters.get(index);
            if (waiter) {
                clearTimeout(waiter.timer);
                screenStartWaiters.delete(index);
                waiter.resolve();
            }
            break;
        }
        case 'SEGMENT_PROGRESS':
            if (!uploadProcessing.hidden && Number.isFinite(Number(message.percent))) {
                setRecordingProgress(Math.max(0, Math.min(99, Number(message.percent))));
            }
            break;
        case 'SEGMENT_READY': {
            const index = Number(message.segmentIndex);
            screenSegmentUploadSessions.delete(index);
            const waiter = screenStopWaiters.get(index);
            if (waiter) {
                clearTimeout(waiter.timer);
                screenStopWaiters.delete(index);
                waiter.resolve(message.upload);
            }
            break;
        }
        case 'SEGMENT_ERROR': {
            const index = Number(message.segmentIndex);
            const waiter = screenStopWaiters.get(index);
            if (waiter) {
                clearTimeout(waiter.timer);
                screenStopWaiters.delete(index);
                waiter.reject(new Error(message.message || 'Ошибка записи экрана.'));
            }
            break;
        }
        case 'REQUEST_STOP_RECORDING':
            if (recordingSessionActive && currentSegment?.mode === 'SCREEN') {
                setCameraError('Демонстрация экрана завершена.');
                await stopRecordingSession();
            }
            break;
        case 'CAPTURE_ENDED':
            screenCaptureReady = false;
            screenCaptureRequestPending = false;
            releaseScreenPreview();
            if (captureMode === 'SCREEN' && !recordingSessionActive) renderScreenPlaceholder();
            break;
        case 'CAPTURE_RELEASED':
            screenCaptureReady = false;
            screenCaptureRequestPending = false;
            releaseScreenPreview();
            break;
        case 'AGENT_CLOSED':
            if (!screenAgentClosing) await handleUnexpectedScreenAgentClose('agent-message');
            break;
        case 'ACTIVATION_REQUIRED':
            reportDesktopEvent('SCREEN_AGENT_ACTIVATION_REQUIRED', JSON.stringify({
                reason: message.reason || '',
                userActivation: !!message.userActivation
            }));
            break;
        case 'WINDOW_GEOMETRY':
            reportDesktopEvent('SCREEN_AGENT_WINDOW_GEOMETRY', JSON.stringify({
                state: message.state || '',
                outerWidth: message.outerWidth || null,
                outerHeight: message.outerHeight || null,
                innerWidth: message.innerWidth || null,
                innerHeight: message.innerHeight || null,
                screenX: message.screenX ?? null,
                screenY: message.screenY ?? null
            }));
            break;
        case 'NATIVE_CAPABILITIES':
            reportDesktopEvent('SCREEN_AGENT_NATIVE_CAPABILITIES', JSON.stringify({
                windowMinimize: !!message.windowMinimize,
                windowSetResizable: !!message.windowSetResizable,
                windowManagementPermission: message.windowManagementPermission || 'unknown',
                chromeAppWindow: !!message.chromeAppWindow,
                chromeWindows: !!message.chromeWindows,
                electronRequire: !!message.electronRequire,
                electronAPI: !!message.electronAPI,
                desktopAPI: !!message.desktopAPI,
                nativeWindow: !!message.nativeWindow
            }));
            break;
        case 'REQUEST_HOST_FOCUS':
            focusBitrixHostWindow();
            break;
        case 'AGENT_PARKED':
            reportDesktopEvent('SCREEN_AGENT_PARKED', JSON.stringify({method: message.method || 'unknown'}));
            break;
        case 'AGENT_ERROR':
            setCameraError(message.message || 'Ошибка записи экрана.');
            break;
        default:
            break;
    }
    fitWindow();
}

function focusBitrixHostWindow() {
    // Run the focus handoff from the embedded app as well as from the capture
    // agent. Some Chromium shells refocus the popup after getDisplayMedia()
    // resolves, so repeat the request for a short period.
    try { window.blur(); } catch (_) { }
    try { window.parent?.focus(); } catch (_) { }
    try { window.top?.focus(); } catch (_) { }
    const delays = [40, 120, 260, 520, 900, 1500, 2200];
    delays.forEach(delay => setTimeout(() => {
        try { window.parent?.focus(); } catch (_) { }
        try { window.top?.focus(); } catch (_) { }
    }, delay));
}

function startScreenAgentWatchdog(agent, id) {
    stopScreenAgentWatchdog();
    screenAgentUnexpectedCloseHandling = false;
    screenAgentWatchdogTimer = setInterval(() => {
        if (screenAgentClosing || screenAgentUnexpectedCloseHandling) return;
        if (screenAgent !== agent || screenAgentId !== id) return;
        let closed = false;
        try { closed = !!agent.closed; } catch (_) { closed = true; }
        if (closed) void handleUnexpectedScreenAgentClose('closed-watchdog');
    }, 250);
}

function stopScreenAgentWatchdog() {
    if (screenAgentWatchdogTimer) clearInterval(screenAgentWatchdogTimer);
    screenAgentWatchdogTimer = null;
}

async function recoverInterruptedScreenUpload(segmentIndex, upload) {
    if (!upload?.id || !upload?.uploadToken) {
        throw new Error('Не удалось определить серверную сессию прерванной записи экрана.');
    }
    reportDesktopEvent('SCREEN_SEGMENT_RECOVERY_STARTED', JSON.stringify({segmentIndex, uploadId: upload.id}));
    // Give already accepted HTTP requests a short grace period. Requests that
    // were cut by closing the popup are discarded by the server; fully stored
    // contiguous chunks remain recoverable.
    await sleep(900);
    const response = await fetchWithTimeout(
        `/bitrix/mobile/uploads/${encodeURIComponent(upload.id)}/recover`,
        {method: 'POST', headers: {'X-Upload-Token': upload.uploadToken}},
        60000);
    let data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось восстановить прерванную запись экрана.');

    const started = Date.now();
    while (data.status !== 'READY') {
        if (data.status === 'ERROR') throw new Error(data.errorMessage || 'Не удалось обработать восстановленную запись экрана.');
        if (Date.now() - started > 20 * 60 * 1000) throw new Error('Восстановление записи экрана заняло слишком много времени.');
        if (!uploadProcessing.hidden) {
            const serverProgress = Math.max(0, Math.min(99, Number(data.processingProgressPercent) || 0));
            setRecordingProgress(Math.max(66, Math.min(99, 66 + Math.floor(serverProgress * 33 / 100))));
        }
        await sleep(250);
        const statusResponse = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(upload.id)}?uploadToken=${encodeURIComponent(upload.uploadToken)}`,
            {cache: 'no-store'},
            10000);
        data = await readJson(statusResponse);
        if (!statusResponse.ok) throw new Error(data.message || 'Не удалось проверить восстановленную запись экрана.');
    }
    screenSegmentUploadSessions.delete(segmentIndex);
    reportDesktopEvent('SCREEN_SEGMENT_RECOVERED', JSON.stringify({segmentIndex, uploadId: upload.id, bytes: data.bytesReceived || 0}));
    return data;
}

function bindRecoveredScreenPromise(segmentIndex, recoveryPromise) {
    segmentPromises[segmentIndex] = recoveryPromise;
    recoveryPromise.catch(() => {});
    const stopWaiter = screenStopWaiters.get(segmentIndex);
    if (stopWaiter) {
        clearTimeout(stopWaiter.timer);
        screenStopWaiters.delete(segmentIndex);
        recoveryPromise.then(stopWaiter.resolve, stopWaiter.reject);
    }
}

async function handleUnexpectedScreenAgentClose(reason) {
    if (screenAgentClosing || screenAgentUnexpectedCloseHandling) return;
    screenAgentUnexpectedCloseHandling = true;
    stopScreenAgentWatchdog();

    reportDesktopEvent('SCREEN_AGENT_UNEXPECTED_CLOSE', JSON.stringify({
        reason: reason || 'unknown',
        recording: !!recordingSessionActive,
        currentMode: currentSegment?.mode || null,
        trackedScreenSegments: screenSegmentUploadSessions.size
    }));

    screenAgentReady = false;
    screenCaptureReady = false;
    screenCaptureRequestPending = false;
    screenAgent = null;
    screenAgentId = null;
    releaseScreenPreview();
    rejectScreenReadyWaiters(new Error('Окно выбора экрана закрыто.'));

    // A segment that never reached SEGMENT_STARTED has no usable recording.
    for (const [index, waiter] of [...screenStartWaiters.entries()]) {
        clearTimeout(waiter.timer);
        waiter.reject(new Error('Окно записи экрана закрыто до начала записи.'));
        screenStartWaiters.delete(index);
    }

    const recoveries = new Map();
    for (const [index, upload] of [...screenSegmentUploadSessions.entries()]) {
        const promise = recoverInterruptedScreenUpload(index, upload);
        recoveries.set(index, promise);
        bindRecoveredScreenPromise(index, promise);
    }

    const interruptedCurrentScreen = recordingSessionActive && currentSegment?.mode === 'SCREEN';
    if (interruptedCurrentScreen) {
        const index = currentSegment.index;
        if (!recoveries.has(index) && !segmentPromises[index]) {
            const failed = Promise.reject(new Error('Окно записи экрана было закрыто до сохранения первой части видео.'));
            failed.catch(() => {});
            segmentPromises[index] = failed;
        }
        currentSegment = null;
        // The screen source no longer exists. Finalize everything already
        // recorded instead of leaving the widget in a dead recording state.
        await stopRecordingSession();
    } else {
        // If camera is the current source, keep recording it. Any older screen
        // segment is recovered in the background and will be included later.
        if (captureMode === 'SCREEN' && !recordingSessionActive) renderScreenPlaceholder();
        setRecordingUi(recordingSessionActive);
        renderCaptureModeUi();
        fitWindow();
    }
    screenAgentUnexpectedCloseHandling = false;
}

async function openScreenCaptureAgent(focus = true) {
    if (screenAgent && !screenAgent.closed) {
        if (screenAgentReady) {
            sendScreenAgentMessage('INIT', {
                contextToken,
                systemAudio: !!captureSystemAudio.checked,
                microphone: !!captureMicrophone.checked
            });
        }
        if (focus) { try { screenAgent.focus(); } catch (_) { } }
        return screenAgent;
    }

    screenAgentClosing = false;
    screenAgentReady = false;
    screenCaptureReady = false;
    releaseScreenPreview();
    screenAgentId = createScreenAgentId();
    const url = `${SCREEN_AGENT_URL}&agentId=${encodeURIComponent(screenAgentId)}`;
    const availLeft = Number(screen.availLeft) || 0;
    const availTop = Number(screen.availTop) || 0;
    const availWidth = Number(screen.availWidth) || Number(screen.width) || SCREEN_AGENT_PICKER_OUTER_WIDTH;
    const availHeight = Number(screen.availHeight) || Number(screen.height) || SCREEN_AGENT_PICKER_OUTER_HEIGHT;
    const left = availLeft + Math.max(0, Math.round((availWidth - SCREEN_AGENT_PICKER_OUTER_WIDTH) / 2));
    const top = availTop + Math.max(0, Math.round((availHeight - SCREEN_AGENT_PICKER_OUTER_HEIGHT) / 2));
    const features = [
        'popup=yes',
        `width=${SCREEN_AGENT_INITIAL_INNER_WIDTH}`,
        `height=${SCREEN_AGENT_INITIAL_INNER_HEIGHT}`,
        `left=${left}`,
        `top=${top}`,
        'resizable=no',
        'scrollbars=no',
        'menubar=no',
        'toolbar=no',
        'location=no',
        'status=no'
    ].join(',');
    screenAgent = window.open(
        url,
        `videoOfferScreenCapture_${screenAgentId.replace(/[^a-zA-Z0-9]/g, '')}`,
        features);
    if (!screenAgent) {
        screenAgentId = null;
        throw new Error('Браузер заблокировал окно выбора экрана. Разрешите всплывающие окна и повторите.');
    }
    startScreenAgentWatchdog(screenAgent, screenAgentId);
    if (focus) { try { screenAgent.focus(); } catch (_) { } }
    return screenAgent;
}

function createScreenAgentId() {
    try { return crypto.randomUUID(); } catch (_) { return `${Date.now()}-${Math.random().toString(36).slice(2)}`; }
}

function sendScreenAgentMessage(type, payload = {}) {
    if (!screenAgent || screenAgent.closed || !screenAgentId) return false;
    try {
        screenAgent.postMessage({channel: SCREEN_AGENT_CHANNEL, agentId: screenAgentId, type, ...payload}, location.origin);
        return true;
    } catch (_) {
        return false;
    }
}

function waitForScreenCaptureReady(timeoutMs = 5 * 60 * 1000) {
    if (screenCaptureReady) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const waiter = {resolve, reject, timer: null};
        waiter.timer = setTimeout(() => {
            screenReadyWaiters = screenReadyWaiters.filter(item => item !== waiter);
            reject(new Error('Экран не был выбран.'));
        }, timeoutMs);
        screenReadyWaiters.push(waiter);
    });
}

function resolveScreenReadyWaiters() {
    const items = screenReadyWaiters.splice(0);
    items.forEach(item => { clearTimeout(item.timer); item.resolve(); });
}

function rejectScreenReadyWaiters(error) {
    const items = screenReadyWaiters.splice(0);
    items.forEach(item => { clearTimeout(item.timer); item.reject(error); });
}

function rejectAllScreenSegmentWaiters(error) {
    for (const [index, waiter] of screenStartWaiters.entries()) {
        clearTimeout(waiter.timer);
        waiter.reject(error);
        screenStartWaiters.delete(index);
    }
    for (const [index, waiter] of screenStopWaiters.entries()) {
        clearTimeout(waiter.timer);
        waiter.reject(error);
        screenStopWaiters.delete(index);
    }
}

async function acceptScreenPreviewOffer(description) {
    if (!description || typeof RTCPeerConnection === 'undefined') return;
    const queuedCandidates = screenPreviewPendingCandidates.splice(0);
    releaseScreenPreviewPeerOnly();
    const peer = new RTCPeerConnection({iceServers: []});
    screenPreviewPeer = peer;
    peer.ontrack = event => {
        const stream = event.streams?.[0] || new MediaStream([event.track]);
        screenPreviewStream = stream;
        if (captureMode === 'SCREEN' && !recordedPreviewUrl) showScreenPreview();
    };
    peer.onicecandidate = event => {
        if (event.candidate) sendScreenAgentMessage('PREVIEW_ICE', {candidate: event.candidate.toJSON ? event.candidate.toJSON() : event.candidate});
    };
    peer.onconnectionstatechange = () => {
        if (peer !== screenPreviewPeer) return;
        if (['failed', 'closed'].includes(peer.connectionState) && !screenPreviewStream) {
            reportDesktopEvent('SCREEN_PREVIEW_WEBRTC_FAILED', peer.connectionState);
        }
    };
    try {
        await peer.setRemoteDescription(description);
        for (const candidate of [...queuedCandidates, ...screenPreviewPendingCandidates.splice(0)]) {
            try { await peer.addIceCandidate(candidate); } catch (_) { }
        }
        const answer = await peer.createAnswer();
        await peer.setLocalDescription(answer);
        sendScreenAgentMessage('PREVIEW_ANSWER', {description: {type: peer.localDescription.type, sdp: peer.localDescription.sdp}});
    } catch (error) {
        reportDesktopEvent('SCREEN_PREVIEW_WEBRTC_ERROR', error?.message || String(error));
        releaseScreenPreviewPeerOnly();
    }
}

async function acceptScreenPreviewIce(candidate) {
    if (!candidate) return;
    if (!screenPreviewPeer || !screenPreviewPeer.remoteDescription) {
        screenPreviewPendingCandidates.push(candidate);
        return;
    }
    try { await screenPreviewPeer.addIceCandidate(candidate); } catch (_) { }
}

function releaseScreenPreviewPeerOnly() {
    if (screenPreviewPeer) {
        try { screenPreviewPeer.ontrack = null; screenPreviewPeer.onicecandidate = null; screenPreviewPeer.close(); } catch (_) { }
    }
    screenPreviewPeer = null;
    screenPreviewPendingCandidates = [];
}

function releaseScreenPreview() {
    if (cameraPreview.srcObject === screenPreviewStream) cameraPreview.srcObject = null;
    screenPreviewStream = null;
    releaseScreenPreviewPeerOnly();
}

function closeScreenCaptureAgent() {
    const agent = screenAgent;
    const id = screenAgentId;
    screenAgentClosing = true;
    stopScreenAgentWatchdog();
    screenAgentReady = false;
    screenCaptureReady = false;
    screenCaptureRequestPending = false;
    releaseScreenPreview();
    rejectScreenReadyWaiters(new Error('Выбор экрана отменён.'));
    rejectAllScreenSegmentWaiters(new Error('Запись экрана завершена.'));
    if (agent && !agent.closed) {
        try { agent.postMessage({channel: SCREEN_AGENT_CHANNEL, agentId: id, type: 'SHUTDOWN'}, location.origin); } catch (_) { }
        try { agent.close(); } catch (_) { }
    }
    screenAgent = null;
    screenAgentId = null;
    screenSegmentUploadSessions.clear();
    setTimeout(() => {
        screenAgentClosing = false;
        screenAgentUnexpectedCloseHandling = false;
    }, 0);
}

async function ensureCameraCapture(switching) {
    if (sourceModeInput.value !== 'RECORD') return false;
    if (!navigator.mediaDevices?.getUserMedia || typeof window.MediaRecorder === 'undefined') {
        setCameraError('Этот браузер не поддерживает запись с камеры.');
        return false;
    }
    clearCameraError();
    startCameraButton.disabled = true;
    startCameraButton.textContent = switching ? 'Переключаем…' : 'Включаем…';
    try {
        stopCameraCapture();
        clearRecordedPreview();
        let video = {width: {ideal: 1280, max: 1280}, height: {ideal: 720, max: 1280}, frameRate: {ideal: 30, max: 30}};
        if (cameraDevices[activeCameraIndex]?.deviceId) {
            video = {...video, deviceId: {exact: cameraDevices[activeCameraIndex].deviceId}};
        }
        const videoStream = await navigator.mediaDevices.getUserMedia({video, audio: false});
        let micStream = null;
        if (captureMicrophone.checked) {
            micStream = await navigator.mediaDevices.getUserMedia({
                video: false,
                audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1}
            });
        }
        cameraStream = new MediaStream();
        const videoTrack = videoStream.getVideoTracks()[0];
        if (!videoTrack) throw new Error('Камера не передала видеодорожку.');
        cameraStream.addTrack(videoTrack);
        const micTrack = micStream?.getAudioTracks?.()[0];
        if (micTrack) cameraStream.addTrack(micTrack);
        // Keep ownership on the composed stream; tracks are the same objects as source streams.
        cameraStream._sourceStreams = [videoStream, micStream].filter(Boolean);
        try { videoTrack.contentHint = 'motion'; } catch (_) { }
        cameraPreview.hidden = false;
        cameraPlaceholder.hidden = true;
        cameraPreview.controls = false;
        cameraPreview.muted = true;
        cameraPreview.autoplay = true;
        cameraPreview.srcObject = cameraStream;
        recordFullscreenButton.hidden = true;
        await cameraPreview.play().catch(async () => {
            await new Promise(resolve => requestAnimationFrame(resolve));
            if (cameraPreview.srcObject === cameraStream) await cameraPreview.play().catch(() => {});
        });
        await refreshCameraDevices();
        const id = videoTrack.getSettings?.().deviceId;
        const index = cameraDevices.findIndex(device => device.deviceId === id);
        if (index >= 0) activeCameraIndex = index;
        reportDesktopEvent('CAMERA_READY', JSON.stringify({settings: videoTrack.getSettings?.() || {}, devices: cameraDevices.length}));
        switchCameraButton.hidden = cameraDevices.length < 2 || captureMode !== 'CAMERA';
        switchCameraButton.disabled = recordingSessionActive;
        recordToggleButton.hidden = false;
        startCameraButton.hidden = true;
        updateRecordButtonState(recordingSessionActive);
        return true;
    } catch (error) {
        stopCameraCapture();
        showCameraPlaceholder();
        setCameraError(cameraErrorMessage(error));
        return false;
    } finally {
        startCameraButton.disabled = false;
        startCameraButton.textContent = captureMode === 'SCREEN' ? 'Выбрать экран' : 'Включить камеру';
        fitWindow();
    }
}

function showCameraPlaceholder() {
    cameraPreview.hidden = true;
    cameraPlaceholder.hidden = false;
    cameraPlaceholderIcon.textContent = '●';
    cameraPlaceholderTitle.textContent = 'Камера выключена';
    cameraPlaceholderText.textContent = captureMicrophone.checked
        ? 'Разрешите доступ к камере и микрофону.' : 'Разрешите доступ к камере.';
    startCameraButton.hidden = recordingSessionActive;
    recordToggleButton.hidden = true;
}

async function refreshCameraDevices() {
    try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        cameraDevices = devices.filter(device => device.kind === 'videoinput');
    } catch (_) { cameraDevices = []; }
}

async function switchCamera() {
    if (recordingSessionActive || captureMode !== 'CAMERA' || cameraDevices.length < 2) return;
    activeCameraIndex = (activeCameraIndex + 1) % cameraDevices.length;
    await ensureCameraCapture(true);
}

function stopCameraCapture() {
    const stream = cameraStream;
    if (stream) {
        try { stream.getTracks().forEach(track => track.stop()); } catch (_) { }
        const sources = stream._sourceStreams || [];
        sources.forEach(source => { try { source.getTracks().forEach(track => track.stop()); } catch (_) { } });
    }
    cameraStream = null;
    if (stream && cameraPreview.srcObject === stream) cameraPreview.srcObject = null;
}

async function toggleRecordingSession() {
    if (captureSwitching) return;
    if (recordingSessionActive) await stopRecordingSession();
    else await startRecordingSession();
}

async function startRecordingSession() {
    clearCameraError();
    setError(null);
    await discardFinalUpload();
    clearRecordedPreview();
    segmentPromises = [];
    nextSegmentIndex = 0;
    currentSegment = null;

    try {
        if (captureMode === 'CAMERA') {
            if (!cameraStream && !(await ensureCameraCapture(false))) return;
        } else {
            if (!screenCaptureReady) {
                await ensureScreenCapture(true);
                if (!screenCaptureReady) return;
            }
        }

        recordingSessionActive = true;
        startSessionTimer();
        setRecordingUi(true);
        const index = nextSegmentIndex++;
        if (captureMode === 'CAMERA') {
            await startCameraSegment(index);
            currentSegment = {mode: 'CAMERA', index};
        } else {
            await startScreenSegment(index);
            currentSegment = {mode: 'SCREEN', index};
        }
    } catch (error) {
        recordingSessionActive = false;
        stopSessionTimer(true);
        setRecordingUi(false);
        setCameraError(error.message || 'Не удалось начать запись');
    }
    fitWindow();
}

async function switchRecordingSource(nextMode) {
    nextMode = nextMode === 'SCREEN' ? 'SCREEN' : 'CAMERA';
    if (!recordingSessionActive || !currentSegment || nextMode === currentSegment.mode || captureSwitching) return;
    captureSwitching = true;
    renderCaptureModeUi();
    setCameraError(null);
    try {
        if (nextMode === 'SCREEN') {
            if (!screenCaptureReady) {
                await ensureScreenCapture(true);
                if (!screenCaptureReady) await waitForScreenCaptureReady();
            }
            const newIndex = nextSegmentIndex++;
            // Start the new source first, then finish the previous one. This keeps the visible recording continuous.
            await startScreenSegment(newIndex);
            const old = currentSegment;
            segmentPromises[old.index] = stopCameraSegment();
            segmentPromises[old.index].finally(() => stopCameraCapture());
            currentSegment = {mode: 'SCREEN', index: newIndex};
            captureMode = 'SCREEN';
            renderCaptureModeUi();
            showScreenPreview();
        } else {
            if (!cameraStream && !(await ensureCameraCapture(false))) {
                throw new Error('Не удалось подготовить камеру для переключения.');
            }
            const newIndex = nextSegmentIndex++;
            await startCameraSegment(newIndex);
            const old = currentSegment;
            segmentPromises[old.index] = stopScreenSegment(old.index);
            currentSegment = {mode: 'CAMERA', index: newIndex};
            captureMode = 'CAMERA';
            renderCaptureModeUi();
        }
    } catch (error) {
        setCameraError(error.message || 'Не удалось переключить источник записи');
    } finally {
        captureSwitching = false;
        renderCaptureModeUi();
        fitWindow();
    }
}

async function stopRecordingSession() {
    if (!recordingSessionActive) return;
    if (!currentSegment && !segmentPromises.some(Boolean)) {
        recordingSessionActive = false;
        captureSwitching = false;
        stopSessionTimer(false);
        setRecordingUi(false);
        renderCaptureModeUi();
        fitWindow();
        return;
    }
    recordToggleButton.disabled = true;
    captureSwitching = true;
    uploadProcessing.hidden = false;
    uploadStatus.textContent = 'Загрузка видео';
    uploadProgressText.textContent = '0%';
    uploadProgressBar.style.width = '0%';
    stopSessionTimer(false);
    updateRecordButtonState(false);

    try {
        const current = currentSegment;
        if (current) {
            if (current.mode === 'CAMERA') segmentPromises[current.index] = stopCameraSegment();
            else if (!segmentPromises[current.index]) segmentPromises[current.index] = stopScreenSegment(current.index);
            currentSegment = null;
        }

        const results = await Promise.all(segmentPromises.filter(Boolean));
        if (!results.length) throw new Error('Запись не содержит видеоданных.');
        // The capture agent is no longer needed once every screen segment is safely READY.
        // Close it before merge/preview work so the technical window disappears as early as possible.
        closeScreenCaptureAgent();
        setRecordingProgress(results.length > 1 ? 98 : 100);
        finalUploadSession = results.length === 1 ? results[0] : await mergeRecordingSegments(results);
        setRecordingProgress(100);
        showNormalizedRecording(finalUploadSession);
    } catch (error) {
        uploadProcessing.hidden = true;
        setCameraError(error.message || 'Не удалось сохранить запись');
        offerFields.hidden = true;
        submitButton.hidden = true;
    } finally {
        recordingSessionActive = false;
        captureSwitching = false;
        recordToggleButton.disabled = false;
        stopCameraCapture();
        closeScreenCaptureAgent();
        setRecordingUi(false);
        renderCaptureModeUi();
        fitWindow();
    }
}

function setRecordingUi(recording) {
    recordingBadge.hidden = !recording;
    updateRecordButtonState(recording);
    sourcePicker.querySelectorAll('button').forEach(button => button.disabled = recording);
    captureMicrophone.disabled = recording;
    captureSystemAudio.disabled = recording;
    switchCameraButton.disabled = recording;
    playRecordingButton.hidden = recording || !recordedPreviewUrl;
    recordToggleButton.hidden = false;
    if (recording) startCameraButton.hidden = true;
    captureModePicker.querySelectorAll('button').forEach(button => button.disabled = captureSwitching);
}

async function startCameraSegment(index) {
    if (!cameraStream?.getVideoTracks?.().length) throw new Error('Камера не готова к записи.');
    if (currentCameraSegment) throw new Error('Камера уже записывает сегмент.');
    const mimeType = chooseRecorderMimeType();
    const recorder = mimeType
        ? new MediaRecorder(cameraStream, {mimeType, videoBitsPerSecond: 3500000, audioBitsPerSecond: 128000})
        : new MediaRecorder(cameraStream, {videoBitsPerSecond: 3500000, audioBitsPerSecond: 128000});
    const uploader = new ChunkUploader(contextToken, recorder.mimeType || mimeType || 'video/webm', 'RECORDING', null, renderRecordingUploadProgress);
    await uploader.init();
    recorder.addEventListener('dataavailable', event => {
        if (event.data?.size > 0) uploader.enqueue(event.data);
    });
    const started = new Promise((resolve, reject) => {
        recorder.addEventListener('start', resolve, {once: true});
        recorder.addEventListener('error', event => reject(event?.error || new Error('MediaRecorder не начал запись')), {once: true});
    });
    recorder.start(MEDIA_CHUNK_INTERVAL_MS);
    await started;
    reportDesktopEvent('CAMERA_RECORDING_STARTED', JSON.stringify({mimeType: recorder.mimeType || mimeType || 'default', videoBitsPerSecond: recorder.videoBitsPerSecond || null, audioBitsPerSecond: recorder.audioBitsPerSecond || null}));
    currentCameraSegment = {index, recorder, uploader, stopPromise: null};
}

function stopCameraSegment() {
    const segment = currentCameraSegment;
    if (!segment) return Promise.reject(new Error('Сегмент камеры не найден.'));
    if (segment.stopPromise) return segment.stopPromise;
    currentCameraSegment = null;
    segment.stopPromise = new Promise((resolve, reject) => {
        segment.recorder.addEventListener('stop', async () => {
            try {
                const ready = await segment.uploader.finish();
                resolve(ready);
            } catch (error) { reject(error); }
        }, {once: true});
        segment.recorder.addEventListener('error', event => reject(event?.error || new Error('Ошибка записи камеры')), {once: true});
        try { segment.recorder.stop(); } catch (error) { reject(error); }
    });
    return segment.stopPromise;
}

async function mergeRecordingSegments(results) {
    const response = await fetchWithTimeout('/bitrix/mobile/uploads/merge', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            contextToken,
            segments: results.map(item => ({uploadId: item.id, uploadToken: item.uploadToken}))
        })
    }, 30 * 60 * 1000);
    const data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось объединить части записи');
    if (data.status !== 'READY') throw new Error(data.errorMessage || 'Объединённая запись не готова');
    return data;
}

function showNormalizedRecording(data) {
    uploadProcessing.hidden = true;
    finalUploadSession = data;
    recordedPreviewUrl = data.previewUrl || '';
    cameraPlaceholder.hidden = true;
    cameraPreview.hidden = false;
    cameraPreview.autoplay = false;
    cameraPreview.controls = true;
    cameraPreview.preload = 'auto';
    cameraPreview.muted = false;
    cameraPreview.srcObject = null;
    cameraPreview.src = recordedPreviewUrl;
    cameraPreview.load();
    prepareVideoFirstFrame(cameraPreview);
    playRecordingButton.hidden = true;
    recordFullscreenButton.hidden = false;
    updatePlayButtonState();
    offerFields.hidden = false;
    submitButton.hidden = false;
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';
    recordToggleButton.hidden = false;
    updateRecordButtonState(false);
    clearCameraError();
}

function updateRecordButtonState(recording) {
    recordToggleButton.classList.toggle('is-recording', !!recording);
    recordToggleButton.setAttribute('aria-label', recording ? 'Остановить запись' : 'Начать новую запись');
    recordToggleButton.title = recording ? 'Остановить запись' : 'Начать новую запись';
}

function startSessionTimer() {
    recordingStartedAt = performance.now();
    renderSessionTimer();
    clearInterval(timerHandle);
    timerHandle = setInterval(renderSessionTimer, 250);
}
function stopSessionTimer(reset) {
    if (!reset) renderSessionTimer();
    clearInterval(timerHandle);
    timerHandle = null;
    if (reset) { recordingStartedAt = null; recordingTimer.textContent = '00:00'; }
}
function renderSessionTimer() {
    if (recordingStartedAt == null) return;
    const seconds = Math.floor((performance.now() - recordingStartedAt) / 1000);
    recordingTimer.textContent = `${String(Math.floor(seconds / 60)).padStart(2,'0')}:${String(seconds % 60).padStart(2,'0')}`;
}

function clearRecordedPreview() {
    recordedPreviewUrl = null;
    // Never pause a live camera/screen srcObject. 023 did this and froze the preview
    // exactly when recording started.
    if (cameraPreview.srcObject) {
        cameraPreview.controls = false;
        cameraPreview.muted = true;
        cameraPreview.autoplay = true;
        if (cameraPreview.paused) cameraPreview.play().catch(() => {});
    } else {
        try { cameraPreview.pause(); } catch (_) { }
        cameraPreview.removeAttribute('src');
        try { cameraPreview.load(); } catch (_) { }
    }
    playRecordingButton.hidden = true;
    recordFullscreenButton.hidden = true;
    exitLocalVideoFullscreen(cameraPreview, recordFullscreenButton);
    updatePlayButtonState();
}

function toggleRecordedPlayback() {
    if (!recordedPreviewUrl || cameraPreview.srcObject) return;
    if (cameraPreview.paused || cameraPreview.ended) {
        if (cameraPreview.ended) cameraPreview.currentTime = 0;
        cameraPreview.play().catch(error => setCameraError(error.message || 'Не удалось воспроизвести запись'));
    } else cameraPreview.pause();
    updatePlayButtonState();
}
function updatePlayButtonState() {
    const playing = !!recordedPreviewUrl && !cameraPreview.paused && !cameraPreview.ended;
    playGlyph.textContent = playing ? 'Ⅱ' : '▶';
    playRecordingButton.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести запись');
    playRecordingButton.title = playing ? 'Пауза' : 'Воспроизвести запись';
}

function startScreenSegment(index) {
    if (!screenCaptureReady || !screenAgent || screenAgent.closed) {
        return Promise.reject(new Error('Экран не выбран.'));
    }
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            screenStartWaiters.delete(index);
            reject(new Error('Запись экрана не запустилась.'));
        }, 20000);
        screenStartWaiters.set(index, {resolve, reject, timer});
        if (!sendScreenAgentMessage('START_SEGMENT', {segmentIndex: index})) {
            clearTimeout(timer);
            screenStartWaiters.delete(index);
            reject(new Error('Окно записи экрана закрыто.'));
        }
    });
}

function stopScreenSegment(index) {
    if (!screenAgent || screenAgent.closed) {
        return Promise.reject(new Error('Окно записи экрана закрыто.'));
    }
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            screenStopWaiters.delete(index);
            reject(new Error('Запись экрана не завершила сохранение.'));
        }, 20 * 60 * 1000);
        screenStopWaiters.set(index, {resolve, reject, timer});
        if (!sendScreenAgentMessage('STOP_SEGMENT', {segmentIndex: index})) {
            clearTimeout(timer);
            screenStopWaiters.delete(index);
            reject(new Error('Окно записи экрана закрыто.'));
        }
    });
}

function initializeFileUpload() {
    chooseFileButton.addEventListener('click', () => { fileInput.value = ''; fileInput.click(); });
    replaceFileButton.addEventListener('click', () => { fileInput.value = ''; fileInput.click(); });
    fileInput.addEventListener('change', async () => {
        const file = fileInput.files?.[0];
        if (!file) return;
        await handleSelectedFile(file);
    });
}

async function handleSelectedFile(file) {
    const generation = ++fileUploadGeneration;
    setError(null);
    clearFilePreview();
    if (activeFileUploader) {
        const previousUploader = activeFileUploader;
        activeFileUploader = null;
        previousUploader.discard().catch(() => {});
    }
    try {
        validateVideoFile(file);
        await discardFinalUpload();
        if (generation !== fileUploadGeneration) return;
        selectedFileCard.hidden = false;
        selectedFileName.textContent = file.name;
        selectedFileMeta.textContent = `${formatBytes(file.size)} · ${file.type || fileExtension(file.name).toUpperCase()}`;
        chooseFileButton.hidden = true;
        offerFields.hidden = true;
        submitButton.hidden = true;
        fileUploadProcessing.hidden = false;
        setFileProgress(0, 'Загрузка видео');

        const uploader = new ChunkUploader(
            contextToken,
            file.type || mimeFromFileName(file.name),
            'FILE',
            file.size,
            progress => {
                if (generation === fileUploadGeneration) renderFileUploadProgress(progress, file.size);
            });
        activeFileUploader = uploader;
        await uploader.init();
        if (generation !== fileUploadGeneration) {
            await uploader.discard().catch(() => {});
            return;
        }
        const ready = await uploader.uploadFile(file, FILE_CHUNK_BYTES);
        if (generation !== fileUploadGeneration) {
            await uploader.discard().catch(() => {});
            return;
        }
        finalUploadSession = ready;
        activeFileUploader = null;
        setFileProgress(100, 'Загрузка видео');
        fileUploadProcessing.hidden = true;
        showFilePreview(finalUploadSession);
        offerFields.hidden = false;
        submitButton.hidden = false;
    } catch (error) {
        if (generation !== fileUploadGeneration || error?.cancelledUpload) return;
        if (activeFileUploader) {
            const failedUploader = activeFileUploader;
            activeFileUploader = null;
            failedUploader.discard().catch(() => {});
        }
        fileUploadProcessing.hidden = true;
        chooseFileButton.hidden = false;
        setError(error.message || 'Не удалось загрузить видеофайл');
    }
    fitWindow();
}

function renderFileUploadProgress(progress, fileSize) {
    const percent = Math.max(0, Math.min(100, Number(progress?.percent) || 0));
    setFileProgress(percent, 'Загрузка видео');
}

function initializeFullscreenControls() {
    fileFullscreenButton?.addEventListener('click', () => toggleVideoFullscreen(filePreview, fileFullscreenButton));
    recordFullscreenButton?.addEventListener('click', () => toggleVideoFullscreen(cameraPreview, recordFullscreenButton));
    document.addEventListener('fullscreenchange', syncFullscreenButtons);
    document.addEventListener('webkitfullscreenchange', syncFullscreenButtons);
    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        exitLocalVideoFullscreen(filePreview, fileFullscreenButton);
        exitLocalVideoFullscreen(cameraPreview, recordFullscreenButton);
    });
    filePreview?.addEventListener('dblclick', () => toggleVideoFullscreen(filePreview, fileFullscreenButton));
    cameraPreview?.addEventListener('dblclick', () => {
        if (recordedPreviewUrl && !cameraPreview.srcObject) toggleVideoFullscreen(cameraPreview, recordFullscreenButton);
    });
}

async function toggleVideoFullscreen(video, button) {
    if (!video) return;
    if (video.classList.contains('is-local-fullscreen')) {
        exitLocalVideoFullscreen(video, button);
        return;
    }
    const current = document.fullscreenElement || document.webkitFullscreenElement;
    if (current) {
        try {
            if (document.exitFullscreen) await document.exitFullscreen();
            else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
        } catch (_) { }
        return;
    }
    try {
        if (video.requestFullscreen) {
            await video.requestFullscreen();
            syncFullscreenButtons();
            return;
        }
        if (video.webkitRequestFullscreen) {
            video.webkitRequestFullscreen();
            return;
        }
        if (video.webkitEnterFullscreen) {
            video.webkitEnterFullscreen();
            return;
        }
    } catch (error) {
        console.warn('[video-offer] native fullscreen rejected; using iframe-local fullscreen', error?.name, error?.message);
    }
    // Bitrix may not delegate the browser fullscreen permission to a placement iframe.
    // In that case fill the entire available application iframe instead of doing nothing.
    video.classList.add('is-local-fullscreen');
    document.body.classList.add('has-local-video-fullscreen');
    if (button) {
        button.classList.add('is-local-exit');
        button.textContent = '×';
        button.title = 'Закрыть полноэкранный режим';
        button.setAttribute('aria-label', 'Закрыть полноэкранный режим');
    }
}

function exitLocalVideoFullscreen(video, button) {
    if (!video) return;
    video.classList.remove('is-local-fullscreen');
    if (!document.querySelector('video.is-local-fullscreen')) document.body.classList.remove('has-local-video-fullscreen');
    if (button) {
        button.classList.remove('is-local-exit');
        button.textContent = '⛶';
        button.title = 'Развернуть видео';
        button.setAttribute('aria-label', 'Развернуть видео');
    }
}

function syncFullscreenButtons() {
    const active = document.fullscreenElement || document.webkitFullscreenElement;
    if (!active) {
        for (const button of [fileFullscreenButton, recordFullscreenButton]) {
            if (!button || button.classList.contains('is-local-exit')) continue;
            button.textContent = '⛶';
            button.title = 'Развернуть видео';
            button.setAttribute('aria-label', 'Развернуть видео');
        }
    }
}

function validateVideoFile(file) {
    if (!file || file.size <= 0) throw new Error('Выбран пустой видеофайл.');
    if (file.size > MAX_FILE_BYTES) throw new Error('Размер видеофайла не должен превышать 100 МБ.');
    const extension = fileExtension(file.name);
    if (!['mp4','mov','webm','mkv','m4v'].includes(extension)) {
        throw new Error('Поддерживаются MP4, MOV, WebM, MKV и M4V.');
    }
}
function showFilePreview(data) {
    filePreviewFrame.hidden = false;
    filePreview.hidden = false;
    filePreview.src = data.previewUrl || '';
    filePreview.preload = 'auto';
    filePreview.load();
    prepareVideoFirstFrame(filePreview);
}
function clearFilePreview() {
    filePreview.pause?.();
    filePreview.removeAttribute('src');
    filePreview.hidden = true;
    filePreviewFrame.hidden = true;
    exitLocalVideoFullscreen(filePreview, fileFullscreenButton);
}
function setFileProgress(percent, text) {
    const normalized = Math.max(0, Math.min(100, Math.round(percent || 0)));
    fileUploadProgressBar.style.width = normalized + '%';
    fileUploadProgressText.textContent = normalized + '%';
    fileUploadStatus.textContent = text;
}

class ChunkUploader {
    constructor(contextTokenValue, mimeType, sourceKind, declaredSizeBytes, progressCallback) {
        this.contextToken = contextTokenValue;
        this.mimeType = mimeType || 'application/octet-stream';
        this.sourceKind = sourceKind || 'RECORDING';
        this.declaredSizeBytes = declaredSizeBytes || null;
        this.progressCallback = progressCallback || (() => {});
        this.session = null;
        this.queue = [];
        this.active = 0;
        this.failure = null;
        this.nextSequence = 0;
        this.completed = 0;
        this.completedBytes = 0;
        this.enqueuedBytes = 0;
        this.inFlightBytes = new Map();
        this.totalExpected = 0;
        this.waiters = [];
        this.generation = 1;
        this.activeRequests = new Set();
    }
    async init() {
        this.emitProgress('starting', 0);
        const response = await fetch('/bitrix/mobile/uploads', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                contextToken: this.contextToken,
                mimeType: this.mimeType,
                sourceKind: this.sourceKind,
                declaredSizeBytes: this.declaredSizeBytes
            })
        });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось создать сессию загрузки');
        this.session = data;
        return data;
    }
    enqueue(blob) {
        if (!blob || blob.size <= 0) return;
        const sequence = this.nextSequence++;
        this.enqueuedBytes += blob.size;
        this.queue.push({sequence, blob, generation: this.generation});
        this.emitProgress('uploading');
        this.pump();
    }
    pump() {
        while (!this.failure && this.active < MAX_PARALLEL_UPLOADS && this.queue.length) {
            const task = this.queue.shift();
            this.active++;
            this.uploadChunk(task.blob, task.sequence)
                .then(() => {
                    if (task.generation !== this.generation) return;
                    this.inFlightBytes.delete(task.sequence);
                    this.completed++;
                    this.completedBytes += task.blob.size;
                    this.emitProgress('uploading');
                })
                .catch(error => {
                    if (task.generation !== this.generation) return;
                    this.inFlightBytes.delete(task.sequence);
                    if (!this.failure) this.failure = error;
                })
                .finally(() => {
                    if (task.generation !== this.generation) return;
                    this.active = Math.max(0, this.active - 1);
                    this.pump();
                    this.notify();
                });
        }
        this.notify();
    }
    progressSnapshot(phase, forcedPercent) {
        const activeBytes = [...this.inFlightBytes.values()].reduce((sum, value) => sum + Math.max(0, Number(value) || 0), 0);
        const loadedBytes = this.completedBytes + activeBytes;
        const totalBytes = Math.max(Number(this.declaredSizeBytes) || 0, this.enqueuedBytes, 1);
        const percent = forcedPercent == null
            ? Math.max(0, Math.min(65, Math.round(loadedBytes * 65 / totalBytes)))
            : Math.max(0, Math.min(100, Math.round(forcedPercent)));
        return {phase, percent, loadedBytes: Math.min(loadedBytes, totalBytes), totalBytes};
    }
    emitProgress(phase, forcedPercent) {
        try { this.progressCallback(this.progressSnapshot(phase, forcedPercent)); } catch (_) { }
    }
    waitDrain(timeoutMs = UPLOAD_DRAIN_TIMEOUT_MS) {
        if (this.failure) return Promise.reject(this.failure);
        if (!this.queue.length && this.active === 0) return Promise.resolve();
        return new Promise((resolve, reject) => {
            const waiter = {resolve, reject, timer: null};
            waiter.timer = setTimeout(() => {
                this.waiters = this.waiters.filter(item => item !== waiter);
                reject(new Error('Не удалось вовремя передать видео на сервер.'));
            }, timeoutMs);
            this.waiters.push(waiter);
        });
    }
    notify() {
        if (this.failure) {
            const items = this.waiters.splice(0);
            items.forEach(item => { clearTimeout(item.timer); item.reject(this.failure); });
            return;
        }
        if (this.queue.length || this.active) return;
        const items = this.waiters.splice(0);
        items.forEach(item => { clearTimeout(item.timer); item.resolve(); });
    }
    async uploadChunk(blob, sequence) {
        let lastError;
        for (let attempt = 1; attempt <= 4; attempt++) {
            this.inFlightBytes.set(sequence, 0);
            try {
                return await this.uploadChunkAttempt(blob, sequence);
            } catch (error) {
                this.inFlightBytes.set(sequence, 0);
                this.emitProgress('uploading');
                lastError = error;
                if (attempt < 4) await sleep(Math.min(1600, attempt * 400));
            }
        }
        throw lastError || new Error('Не удалось загрузить часть видео');
    }
    uploadChunkAttempt(blob, sequence) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            this.activeRequests.add(xhr);
            xhr.open('PUT', `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/chunks/${sequence}`, true);
            xhr.setRequestHeader('Content-Type', 'application/octet-stream');
            xhr.setRequestHeader('X-Upload-Token', this.session.uploadToken);
            xhr.timeout = CHUNK_UPLOAD_TIMEOUT_MS;
            xhr.upload.onprogress = event => {
                const loaded = Math.min(blob.size, Math.max(0, Number(event.loaded) || 0));
                this.inFlightBytes.set(sequence, loaded);
                this.emitProgress('uploading');
            };
            xhr.onload = () => {
                this.activeRequests.delete(xhr);
                const data = parseJsonText(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300) resolve(data);
                else reject(new Error(data.message || 'Сервер не принял часть видео'));
            };
            xhr.onerror = () => { this.activeRequests.delete(xhr); reject(new Error('Ошибка сети при загрузке видео')); };
            xhr.ontimeout = () => { this.activeRequests.delete(xhr); reject(new Error('Сервер слишком долго принимал видео')); };
            xhr.onabort = () => { this.activeRequests.delete(xhr); reject(new Error('Загрузка видео отменена')); };
            xhr.send(blob);
        });
    }
    async finish(timeoutMs = UPLOAD_DRAIN_TIMEOUT_MS) {
        this.totalExpected = this.nextSequence;
        await this.waitDrain(timeoutMs);
        if (this.failure) throw this.failure;
        if (this.totalExpected <= 0) throw new Error('Видео не содержит данных.');
        this.emitProgress('uploaded', 65);
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/complete?chunkCount=${encodeURIComponent(this.totalExpected)}`,
            {method: 'POST', headers: {'X-Upload-Token': this.session.uploadToken}}, 60000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось завершить загрузку видео');
        this.session = data;
        this.emitProgress('processing', 66);
        return this.waitReady();
    }
    async waitReady() {
        const started = Date.now();
        while (Date.now() - started < 20 * 60 * 1000) {
            const response = await fetchWithTimeout(
                `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}?uploadToken=${encodeURIComponent(this.session.uploadToken)}`,
                {cache: 'no-store'}, 10000);
            const data = await readJson(response);
            if (!response.ok) throw new Error(data.message || 'Не удалось проверить обработку видео');
            this.session = data;
            if (data.status === 'READY') {
                this.emitProgress('ready', this.sourceKind === 'RECORDING' ? 99 : 100);
                return data;
            }
            if (data.status === 'ERROR') throw new Error(data.errorMessage || 'Не удалось обработать видео');
            const serverProgress = Math.max(0, Math.min(99, Number(data.processingProgressPercent) || 0));
            this.emitProgress('processing', Math.max(66, Math.min(99, 66 + Math.floor(serverProgress * 33 / 100))));
            await sleep(250);
        }
        throw new Error('Обработка видео заняла слишком много времени');
    }
    async uploadFile(file, chunkBytes) {
        const count = Math.ceil(file.size / chunkBytes);
        this.totalExpected = count;
        for (let i = 0; i < count; i++) {
            this.enqueue(file.slice(i * chunkBytes, Math.min(file.size, (i + 1) * chunkBytes)));
        }
        return this.finish(Math.max(UPLOAD_DRAIN_TIMEOUT_MS, 10 * 60 * 1000));
    }
    cancel() {
        this.generation++;
        const error = new Error('Загрузка заменена');
        error.cancelledUpload = true;
        this.failure = error;
        this.queue = [];
        this.inFlightBytes.clear();
        this.activeRequests.forEach(xhr => { try { xhr.abort(); } catch (_) { } });
        this.activeRequests.clear();
        this.notify();
    }
    async discard() {
        const session = this.session;
        this.cancel();
        if (!session?.id || !session?.uploadToken || session.status === 'CONSUMED') return;
        try {
            await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(session.id)}`, {
                method: 'DELETE', headers: {'X-Upload-Token': session.uploadToken}
            });
        } catch (_) { }
    }
}

async function discardFinalUpload() {
    const previous = finalUploadSession;
    finalUploadSession = null;
    if (!previous || previous.status === 'CONSUMED') return;
    try {
        await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(previous.id)}`, {
            method: 'DELETE', headers: {'X-Upload-Token': previous.uploadToken}
        });
    } catch (_) { }
}

async function resetTransientMedia() {
    if (recordingSessionActive) return;
    fileUploadGeneration++;
    if (activeFileUploader) {
        const uploader = activeFileUploader;
        activeFileUploader = null;
        uploader.discard().catch(() => {});
    }
    stopCameraCapture();
    closeScreenCaptureAgent();
    clearRecordedPreview();
    clearFilePreview();
    selectedFileCard.hidden = true;
    chooseFileButton.hidden = false;
    fileInput.value = '';
    fileUploadProcessing.hidden = true;
    uploadProcessing.hidden = true;
    offerFields.hidden = sourceModeInput.value !== 'LINK';
    await discardFinalUpload();
}


function renderRecordingUploadProgress(progress) {
    if (uploadProcessing.hidden) return;
    const percent = Math.max(0, Math.min(99, Number(progress?.percent) || 0));
    setRecordingProgress(percent);
}

function setRecordingProgress(percent) {
    const normalized = Math.max(0, Math.min(100, Math.round(Number(percent) || 0)));
    uploadStatus.textContent = 'Загрузка видео';
    uploadProgressText.textContent = normalized + '%';
    uploadProgressBar.style.width = normalized + '%';
}

function prepareVideoFirstFrame(video) {
    if (!video) return;
    const reveal = () => {
        try {
            video.pause();
            const duration = Number(video.duration);
            if (Number.isFinite(duration) && duration > 0.05) video.currentTime = Math.min(0.05, duration / 10);
        } catch (_) { }
    };
    if (video.readyState >= 2) reveal();
    else video.addEventListener('loadeddata', reveal, {once: true});
}

function featureAllowed(name) {
    const policy = document.permissionsPolicy || document.featurePolicy;
    if (!policy || typeof policy.allowsFeature !== 'function') return null;
    try { return !!policy.allowsFeature(name); } catch (_) { return null; }
}

function reportDesktopEvent(event, details) {
    try {
        fetch('/bitrix/client-events', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({contextToken, event, details: details || ''}),
            keepalive: true
        }).catch(() => {});
    } catch (_) { }
}

function cameraErrorMessage(error) {
    const name = error?.name || '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') return 'Доступ к камере или микрофону запрещён. Разрешите его для Bitrix24 и повторите попытку.';
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return 'Камера или микрофон не найдены на компьютере.';
    if (name === 'NotReadableError' || name === 'TrackStartError') return 'Камера или микрофон заняты другим приложением либо недоступны.';
    return error?.message || 'Не удалось открыть камеру.';
}
function setCameraError(message) { cameraError.hidden = !message; cameraError.textContent = message || ''; }
function clearCameraError() { setCameraError(null); }

async function checkStatus() {
    if (!activeOfferId) return;
    try {
        const response = await fetch('/api/video-offers/' + activeOfferId, {cache: 'no-store'});
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось проверить статус');
        renderOffer(data);
        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING' && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'ERROR' || data.status === 'CANCELLED' || (data.status === 'READY' && deliveryFinished)) {
            clearInterval(pollTimer);
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        }
    } catch (error) { finishWithError(error.message); }
}

function renderOffer(data) {
    const percent = Math.max(0, Math.min(100, Number(data.progressPercent) || 0));
    if (data.status === 'ERROR') {
        processing.hidden = true;
        setError(data.errorMessage || 'Не удалось подготовить видеооффер');
        fitWindow();
        return;
    }
    if (data.status === 'CANCELLED') {
        processing.hidden = true;
        setError('Подготовка видео отменена');
        fitWindow();
        return;
    }
    if (data.status !== 'READY') {
        readyResult.hidden = true;
        deliveryStatus.textContent = '';
        renderProgress(percent, 'Загрузка видео');
    } else if (data.bitrixDeliveryStatus === 'DELIVERED' || data.bitrixDeliveryStatus === 'NOT_REQUIRED') {
        processing.hidden = true;
        readyResult.hidden = false;
        deliveryStatus.textContent = '';
        readyMessage.textContent = 'Готовое сообщение для отправки добавлено в таймлайн. ' + viewGoalMessage(data.viewNotificationGoal);
    } else if (data.bitrixDeliveryStatus === 'ERROR') {
        processing.hidden = true;
        readyResult.hidden = false;
        deliveryStatus.textContent = '';
        readyMessage.textContent = data.bitrixDeliveryError
            ? 'Видео готово, но сообщение для отправки не удалось добавить в карточку: ' + data.bitrixDeliveryError
            : 'Видео готово, но сообщение для отправки не удалось добавить в карточку Bitrix24.';
    } else {
        readyResult.hidden = true;
        deliveryStatus.textContent = '';
        renderProgress(100, 'Загрузка видео');
    }
    fitWindow();
}

function renderProgress(percent, text) {
    processing.hidden = false;
    progressBar.style.width = percent + '%';
    processingPercent.textContent = percent + '%';
    processingStatus.textContent = text;
}
function finishWithError(message) {
    clearInterval(pollTimer);
    setError(message);
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';
    fitWindow();
}
function setError(message) { formError.hidden = !message; formError.textContent = message || ''; }

function chooseRecorderMimeType() {
    if (typeof window.MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    return ['video/mp4;codecs=avc1.42E01E,mp4a.40.2','video/mp4','video/webm;codecs=vp8,opus','video/webm']
        .find(type => MediaRecorder.isTypeSupported(type)) || '';
}
function fileExtension(name) { const match = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/); return match ? match[1] : ''; }
function mimeFromFileName(name) { return ({mp4:'video/mp4',mov:'video/quicktime',webm:'video/webm',mkv:'video/x-matroska',m4v:'video/x-m4v'})[fileExtension(name)] || 'application/octet-stream'; }
function formatBytes(bytes) { const n=Number(bytes)||0; if(n<1024)return n+' Б'; if(n<1024*1024)return (n/1024).toFixed(1)+' КБ'; return (n/1024/1024).toFixed(1)+' МБ'; }
async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs);
    try { return await fetch(url, {...(options || {}), signal: controller.signal}); }
    catch (error) { if (error?.name === 'AbortError') throw new Error('Сервер слишком долго не отвечает'); throw error; }
    finally { clearTimeout(timer); }
}
async function readJson(response) { const text = await response.text(); if (!text) return {}; try { return JSON.parse(text); } catch (_) { return {message: text}; } }
function parseJsonText(text) { if (!text) return {}; try { return JSON.parse(text); } catch (_) { return {message: text}; } }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(function () { BX24.resizeWindow(860, 860); BX24.fitWindow(); });
}
function fitWindow() { if (typeof BX24 === 'undefined') return; try { BX24.fitWindow(); } catch (_) { } }
function viewGoalMessage(goal) {
    return ({
        ONE_MINUTE:'После 1 минуты просмотра ответственному будет поставлено дело и придёт уведомление.',
        HALF:'После просмотра 50% ответственному будет поставлено дело и придёт уведомление.',
        COMPLETED:'После полного просмотра ответственному будет поставлено дело и придёт уведомление.',
        NONE:'Автоматическое дело по просмотру отключено.'
    })[goal] || '';
}

function initializeClientMessage() {
    if (!clientMessageInput) return;
    clientMessageInput.addEventListener('input', () => {
        clientMessageInput.dataset.userEdited = 'true';
    });
    clientMessageLoadPromise = loadClientMessageTemplate(contextToken);
}

async function loadClientMessageTemplate(token) {
    if (!clientMessageInput || !token) return;
    clientMessageInput.disabled = true;
    try {
        const response = await fetchWithTimeout(
            '/bitrix/client-message?contextToken=' + encodeURIComponent(token),
            {cache: 'no-store'},
            10000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось сформировать сообщение клиенту');
        if (clientMessageInput.dataset.userEdited !== 'true') {
            clientMessageInput.value = displayClientMessageTemplate(data.message || defaultClientMessageTemplate());
        }
        if (accompanyingTextInput && !accompanyingTextInput.value.trim() && data.accompanyingText) {
            accompanyingTextInput.value = data.accompanyingText;
        }
        reportDesktopEvent('CLIENT_MESSAGE_READY', 'employeeId=' + (data.employeeId || '') + ';name=' + (data.employeeName || ''));
    } catch (error) {
        if (clientMessageInput.dataset.userEdited !== 'true') {
            clientMessageInput.value = displayClientMessageTemplate(defaultClientMessageTemplate());
        }
        console.warn('[video-offer] client message template fallback:', error?.message || error);
    } finally {
        clientMessageInput.disabled = false;
    }
}

function displayClientMessageTemplate(value) {
    return String(value || '').replaceAll('{{VIDEO_URL}}', '〔ссылка на видео〕');
}

function defaultClientMessageTemplate() {
    return 'В продолжение нашего разговора подготовил для вас короткую видеопрезентацию.\n\n'
        + 'Посмотреть можно по ссылке:\n{{VIDEO_URL}}';
}


// 034 · tenant/client administration inside desktop Bitrix24
let clientSettingsState = null;
let clientSettingsActiveTab = 'employees';
let clientSettingsEditingUserId = null;
let clientOffers = [];
let clientOffersLoaded = false;
let clientOfferSort = { key: 'createdAt', direction: 'desc', period: '7' };

function initializeClientSettings() {
    if (!openClientSettingsButton || !clientSettingsModal) return;
    openClientSettingsButton.hidden = !currentUserAdmin;
    if (!currentUserAdmin) return;

    openClientSettingsButton.addEventListener('click', openClientSettings);
    document.getElementById('close-client-settings')?.addEventListener('click', closeClientSettings);
    clientSettingsModal.addEventListener('click', event => { if (event.target === clientSettingsModal) closeClientSettings(); });
    document.getElementById('sync-client-users')?.addEventListener('click', syncClientUsers);
    document.getElementById('client-offer-period')?.addEventListener('change', event => {
        clientOfferSort.period = event.target.value || '7';
        renderClientOffers();
    });
    document.querySelectorAll('[data-client-settings-tab]').forEach(button => {
        button.addEventListener('click', () => switchClientSettingsTab(button.dataset.clientSettingsTab));
    });
    document.querySelectorAll('[data-client-offer-sort]').forEach(button => {
        button.addEventListener('click', () => changeClientOfferSort(button.dataset.clientOfferSort));
    });

    const employeeModal = document.getElementById('client-employee-modal');
    document.getElementById('close-client-employee-modal')?.addEventListener('click', closeClientEmployeeModal);
    document.getElementById('save-client-employee')?.addEventListener('click', saveClientEmployeeSettings);
    employeeModal?.addEventListener('click', event => { if (event.target === employeeModal) closeClientEmployeeModal(); });

    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        if (employeeModal && !employeeModal.hidden) return closeClientEmployeeModal();
        if (!clientSettingsModal.hidden) closeClientSettings();
    });
}

async function openClientSettings() {
    clientSettingsActiveTab = 'employees';
    clientOffersLoaded = false;
    clientOffers = [];
    clientSettingsModal.hidden = false;
    clientSettingsModal.setAttribute('aria-hidden', 'false');
    document.getElementById('client-settings-subtitle').textContent = tenantName || 'Компания Bitrix24';
    await loadClientSettings();
    fitWindow();
}

function closeClientSettings() {
    closeClientEmployeeModal();
    if (!clientSettingsModal) return;
    clientSettingsModal.hidden = true;
    clientSettingsModal.setAttribute('aria-hidden', 'true');
}

async function loadClientSettings() {
    const loading = document.getElementById('client-settings-loading');
    const content = document.getElementById('client-settings-content');
    const error = document.getElementById('client-settings-error');
    loading.hidden = false;
    content.hidden = true;
    error.hidden = true;
    try {
        const response = await fetch('/bitrix/settings?contextToken=' + encodeURIComponent(contextToken), { cache: 'no-store' });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось загрузить настройки');
        clientSettingsState = data;
        renderClientSettings();
        loading.hidden = true;
        content.hidden = false;
    } catch (errorValue) {
        loading.hidden = true;
        error.textContent = errorValue.message || 'Не удалось загрузить настройки';
        error.hidden = false;
    }
}

function renderClientSettings() {
    const data = clientSettingsState;
    if (!data) return;
    const human = value => {
        const n = Number(value || 0);
        if (n >= 1024 ** 3) return (n / 1024 ** 3).toFixed(2) + ' ГБ';
        return (n / 1024 ** 2).toFixed(1) + ' МБ';
    };
    document.getElementById('client-settings-metrics').innerHTML = `
        <div class="client-settings-metric"><span>Пакет</span><b>${escapeClientSettingsHtml(data.packageName || '—')}</b><small>${data.seatsUsed} из ${data.seatLimit} сотрудников</small></div>
        <div class="client-settings-metric"><span>Видеоофферы</span><b>${data.offersRemaining}</b><small>осталось из ${data.offerLimit}</small></div>
        <div class="client-settings-metric"><span>Диск</span><b>${human(data.diskRemainingBytes)}</b><small>свободно из ${human(data.diskQuotaBytes)}</small></div>`;
    renderClientSettingsTabs();
    renderClientEmployeePools();
    if (clientSettingsActiveTab === 'offers') renderClientOffers();
}

function renderClientSettingsTabs() {
    document.querySelectorAll('[data-client-settings-tab]').forEach(button => {
        button.classList.toggle('is-active', button.dataset.clientSettingsTab === clientSettingsActiveTab);
    });
    const employeePanel = document.getElementById('client-settings-employees-panel');
    const offersPanel = document.getElementById('client-settings-offers-panel');
    if (employeePanel) employeePanel.hidden = clientSettingsActiveTab !== 'employees';
    if (offersPanel) offersPanel.hidden = clientSettingsActiveTab !== 'offers';
}

async function switchClientSettingsTab(tab) {
    if (!['employees', 'offers'].includes(tab)) return;
    clientSettingsActiveTab = tab;
    renderClientSettingsTabs();
    if (tab === 'offers') {
        await loadClientOffers();
    } else {
        renderClientEmployeePools();
    }
    fitWindow();
}

function activeClientUsers() {
    return (clientSettingsState?.users || []).filter(user => user.active !== false);
}

function renderClientEmployeePools() {
    const data = clientSettingsState;
    if (!data) return;
    const users = activeClientUsers();
    const selected = users.filter(user => user.offerAccess);
    const available = users.filter(user => !user.offerAccess);
    const availableList = document.getElementById('client-users-available');
    const selectedList = document.getElementById('client-users-selected');
    if (!availableList || !selectedList) return;

    availableList.innerHTML = available.map(user => clientEmployeeCard(user, false)).join('') || '<div class="client-drag-empty">Нет сотрудников</div>';
    selectedList.innerHTML = selected.map(user => clientEmployeeCard(user, true)).join('') || '<div class="client-drag-empty">Перетащите сотрудника сюда</div>';
    document.getElementById('client-available-count').textContent = String(available.length);
    document.getElementById('client-selected-count').textContent = `${selected.length} / ${data.seatLimit}`;
    const limit = selected.length >= Number(data.seatLimit || 0);
    document.getElementById('client-seat-limit')?.classList.toggle('is-visible', limit);

    selectedList.querySelectorAll('[data-client-user-gear]').forEach(button => {
        button.addEventListener('click', event => {
            event.stopPropagation();
            openClientEmployeeModal(Number(button.dataset.clientUserGear));
        });
    });
    selectedList.querySelectorAll('[data-client-user-remove]').forEach(button => {
        button.addEventListener('click', async event => {
            event.stopPropagation();
            await removeClientUserAccess(Number(button.dataset.clientUserRemove));
        });
    });
    initClientEmployeeDragDrop();
}

function clientEmployeeCard(user, selected) {
    const controls = selected ? `
        <div class="client-employee-card-actions">
            <button class="client-employee-card-action" type="button" data-client-user-gear="${user.bitrixUserId}" title="Настроить" aria-label="Настроить">${clientGearSvg()}</button>
            <button class="client-employee-card-action is-remove" type="button" data-client-user-remove="${user.bitrixUserId}" title="Удалить" aria-label="Удалить" ${user.primaryAdmin ? 'disabled' : ''}>✕</button>
        </div>` : '';
    return `<div class="client-employee-drag-item" data-client-user-id="${user.bitrixUserId}" data-primary="${user.primaryAdmin ? 'true' : 'false'}">
        <span class="client-employee-drag-handle" aria-hidden="true">${clientUserSvg()}</span>
        <div class="client-employee-drag-main"><div class="client-employee-drag-name">${escapeClientSettingsHtml(user.displayName || ('Bitrix ID ' + user.bitrixUserId))}</div><div class="client-employee-drag-meta">${escapeClientSettingsHtml(user.email || ('Bitrix ID ' + user.bitrixUserId))}${selected ? ` · ${user.offersUsed || 0} офф.` : ''}${user.primaryAdmin ? ' · главный администратор' : user.admin ? ' · администратор' : ''}</div></div>
        ${controls}
    </div>`;
}

async function syncClientUsers() {
    setClientSettingsBusy(true);
    try {
        const response = await fetch('/bitrix/settings/sync-users?contextToken=' + encodeURIComponent(contextToken), { method: 'POST' });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось обновить сотрудников');
        clientSettingsState = data;
        renderClientSettings();
    } catch (error) {
        showClientSettingsError(error.message);
    } finally {
        setClientSettingsBusy(false);
    }
}

async function updateClientUser(request) {
    setClientSettingsBusy(true);
    try {
        const response = await fetch('/bitrix/settings/users?contextToken=' + encodeURIComponent(contextToken), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ users: [request] })
        });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось сохранить сотрудника');
        clientSettingsState = data;
        renderClientSettings();
        return data;
    } finally {
        setClientSettingsBusy(false);
    }
}

async function removeClientUserAccess(userId) {
    const user = activeClientUsers().find(item => Number(item.bitrixUserId) === Number(userId));
    if (!user || user.primaryAdmin) return;
    try {
        await updateClientUser({
            bitrixUserId: Number(user.bitrixUserId),
            offerAccess: false,
            admin: false,
            defaultAccompanyingText: user.defaultAccompanyingText || null,
            defaultClientMessage: user.defaultClientMessage || null
        });
    } catch (error) {
        showClientSettingsError(error.message);
        await loadClientSettings();
    }
}

function openClientEmployeeModal(userId) {
    const user = activeClientUsers().find(item => Number(item.bitrixUserId) === Number(userId));
    const modal = document.getElementById('client-employee-modal');
    if (!user || !modal || !user.offerAccess) return;
    clientSettingsEditingUserId = Number(userId);
    document.getElementById('client-employee-modal-subtitle').textContent = user.displayName || ('Bitrix ID ' + userId);
    const admin = document.getElementById('client-employee-admin');
    admin.checked = !!user.admin;
    admin.disabled = !!user.primaryAdmin;
    document.getElementById('client-employee-accompanying').value = user.defaultAccompanyingText || '';
    document.getElementById('client-employee-message').value = user.defaultClientMessage || '';
    document.getElementById('client-employee-modal-error').hidden = true;
    modal.hidden = false;
    modal.setAttribute('aria-hidden', 'false');
}

function closeClientEmployeeModal() {
    const modal = document.getElementById('client-employee-modal');
    if (!modal) return;
    modal.hidden = true;
    modal.setAttribute('aria-hidden', 'true');
    clientSettingsEditingUserId = null;
}

async function saveClientEmployeeSettings() {
    const user = activeClientUsers().find(item => Number(item.bitrixUserId) === Number(clientSettingsEditingUserId));
    if (!user) return;
    const button = document.getElementById('save-client-employee');
    button.disabled = true;
    const error = document.getElementById('client-employee-modal-error');
    error.hidden = true;
    try {
        await updateClientUser({
            bitrixUserId: Number(user.bitrixUserId),
            offerAccess: true,
            admin: user.primaryAdmin ? true : !!document.getElementById('client-employee-admin').checked,
            defaultAccompanyingText: document.getElementById('client-employee-accompanying').value || null,
            defaultClientMessage: document.getElementById('client-employee-message').value || null
        });
        closeClientEmployeeModal();
    } catch (e) {
        error.textContent = e.message || 'Не удалось сохранить сотрудника';
        error.hidden = false;
    } finally {
        button.disabled = false;
    }
}

function initClientEmployeeDragDrop() {
    const available = document.getElementById('client-users-available');
    const selected = document.getElementById('client-users-selected');
    if (!available || !selected) return;
    bindClientPhysicalDragGroup('client-employees', [available, selected], '.client-employee-drag-item[data-client-user-id]', {
        canStart: ({ item, sourceContainer }) => {
            if (sourceContainer === selected && item.dataset.primary === 'true') return false;
            if (sourceContainer === available && clientSelectedCount() >= Number(clientSettingsState?.seatLimit || 0)) {
                signalClientSeatLimit(item);
                return false;
            }
            return true;
        },
        onDrop: async ({ item, sourceContainer, targetContainer }) => {
            if (!item || sourceContainer === targetContainer) return renderClientEmployeePools();
            const id = Number(item.dataset.clientUserId);
            const user = activeClientUsers().find(entry => Number(entry.bitrixUserId) === id);
            if (!user) return renderClientEmployeePools();
            const enabling = targetContainer === selected;
            if (enabling && clientSelectedCount() >= Number(clientSettingsState?.seatLimit || 0)) {
                signalClientSeatLimit(item);
                return renderClientEmployeePools();
            }
            try {
                await updateClientUser({
                    bitrixUserId: id,
                    offerAccess: enabling,
                    admin: enabling ? !!user.admin : false,
                    defaultAccompanyingText: user.defaultAccompanyingText || null,
                    defaultClientMessage: user.defaultClientMessage || null
                });
            } catch (error) {
                showClientSettingsError(error.message);
                await loadClientSettings();
            }
        }
    });
}

function clientSelectedCount() {
    return activeClientUsers().filter(user => user.offerAccess).length;
}

function signalClientSeatLimit(item) {
    item?.classList.remove('drag-denied');
    void item?.offsetWidth;
    item?.classList.add('drag-denied');
    const notice = document.getElementById('client-seat-limit');
    if (!notice) return;
    notice.classList.add('is-visible', 'is-flashing');
    setTimeout(() => notice.classList.remove('is-flashing'), 430);
}

const clientDragGroups = {};
function bindClientPhysicalDragGroup(key, containers, selector, options = {}) {
    let group = clientDragGroups[key];
    if (!group) {
        group = { key, containers: [], selector, options, bound: new WeakSet(), pointerId: null, item: null, placeholder: null, sourceContainer: null, activeContainer: null, startX: 0, startY: 0, offsetX: 0, offsetY: 0, dragging: false };
        clientDragGroups[key] = group;
    }
    group.containers = containers;
    group.selector = selector;
    group.options = options;
    containers.forEach(container => {
        if (group.bound.has(container)) return;
        group.bound.add(container);
        container.addEventListener('pointerdown', event => clientDragPointerDown(event, group));
    });
}
function clientDragPointerDown(event, group) {
    if (event.button !== undefined && event.button !== 0) return;
    if (event.target.closest('button,input,select,textarea,a')) return;
    const item = event.target.closest(group.selector);
    if (!item) return;
    const sourceContainer = item.parentElement;
    if (!group.containers.includes(sourceContainer)) return;
    if (typeof group.options.canStart === 'function' && !group.options.canStart({ item, sourceContainer })) { event.preventDefault(); return; }
    Object.assign(group, { pointerId: event.pointerId, item, sourceContainer, activeContainer: sourceContainer, startX: event.clientX, startY: event.clientY, dragging: false });
    item.setPointerCapture?.(event.pointerId);
    const move = e => clientDragPointerMove(e, group, move, up);
    const up = e => clientDragPointerUp(e, group, move, up);
    document.addEventListener('pointermove', move, { passive: false });
    document.addEventListener('pointerup', up, { passive: false });
    document.addEventListener('pointercancel', up, { passive: false });
}
function clientDragPointerMove(event, group, move, up) {
    if (group.pointerId !== null && event.pointerId !== group.pointerId) return;
    if (!group.dragging) {
        if (Math.hypot(event.clientX - group.startX, event.clientY - group.startY) < 4) return;
        startClientPhysicalDrag(event, group);
    }
    event.preventDefault();
    group.item.style.left = (event.clientX - group.offsetX) + 'px';
    group.item.style.top = (event.clientY - group.offsetY) + 'px';
    updateClientDragPlaceholder(event, group);
    autoscrollClientDrag(event, group);
}
function startClientPhysicalDrag(event, group) {
    const item = group.item;
    const rect = item.getBoundingClientRect();
    group.offsetX = event.clientX - rect.left;
    group.offsetY = event.clientY - rect.top;
    const placeholder = document.createElement('div');
    placeholder.className = 'client-physical-drag-placeholder';
    placeholder.style.width = rect.width + 'px';
    placeholder.style.height = rect.height + 'px';
    item.parentElement.insertBefore(placeholder, item);
    group.placeholder = placeholder;
    item.classList.add('client-physical-drag-floating');
    for (const [name, value] of Object.entries({ position: 'fixed', left: rect.left + 'px', top: rect.top + 'px', width: rect.width + 'px', 'min-width': rect.width + 'px', 'max-width': rect.width + 'px', height: rect.height + 'px', 'z-index': '7000', margin: '0' })) item.style.setProperty(name, value, 'important');
    document.body.appendChild(item);
    group.dragging = true;
    document.body.classList.add('client-is-physical-dragging');
}
function updateClientDragPlaceholder(event, group) {
    const target = clientDragTargetContainer(event.clientX, event.clientY, group);
    if (!target || !group.placeholder) return;
    const items = [...target.querySelectorAll(group.selector)].filter(el => el !== group.item);
    let before = null;
    for (const child of items) {
        const rect = child.getBoundingClientRect();
        if (event.clientY < rect.top + rect.height / 2) { before = child; break; }
    }
    if (!before) before = target.querySelector('.client-drag-empty');
    target.insertBefore(group.placeholder, before);
    group.activeContainer = target;
    group.containers.forEach(container => container.classList.toggle('is-drag-target', container === target));
}
function clientDragTargetContainer(x, y, group) {
    for (const element of document.elementsFromPoint(x, y)) {
        for (const container of group.containers) if (element === container || container.contains(element)) return container;
    }
    for (const container of group.containers) {
        const rect = container.getBoundingClientRect();
        if (x >= rect.left - 20 && x <= rect.right + 20 && y >= rect.top - 28 && y <= rect.bottom + 28) return container;
    }
    return group.activeContainer || group.sourceContainer;
}
function autoscrollClientDrag(event, group) {
    let host = group.activeContainer;
    while (host && host !== document.body) {
        const style = getComputedStyle(host);
        if (/(auto|scroll)/.test(style.overflowY) && host.scrollHeight > host.clientHeight) break;
        host = host.parentElement;
    }
    if (!host || host === document.body) host = document.scrollingElement || document.documentElement;
    const rect = host.getBoundingClientRect();
    const edge = 52;
    if (event.clientY < rect.top + edge) host.scrollTop -= 12;
    else if (event.clientY > rect.bottom - edge) host.scrollTop += 12;
}
function clientDragPointerUp(event, group, move, up) {
    document.removeEventListener('pointermove', move);
    document.removeEventListener('pointerup', up);
    document.removeEventListener('pointercancel', up);
    if (!group.dragging) return resetClientDrag(group);
    event.preventDefault();
    const item = group.item;
    const placeholder = group.placeholder;
    const target = placeholder?.parentElement || group.sourceContainer;
    if (item && placeholder && target) { target.insertBefore(item, placeholder); placeholder.remove(); }
    cleanupClientFloating(item);
    group.containers.forEach(container => container.classList.remove('is-drag-target'));
    document.body.classList.remove('client-is-physical-dragging');
    if (typeof group.options.onDrop === 'function') group.options.onDrop({ item, sourceContainer: group.sourceContainer, targetContainer: target, containers: group.containers });
    resetClientDrag(group);
}
function cleanupClientFloating(item) {
    if (!item) return;
    item.classList.remove('client-physical-drag-floating');
    ['position', 'left', 'top', 'width', 'min-width', 'max-width', 'height', 'z-index', 'margin'].forEach(name => item.style.removeProperty(name));
}
function resetClientDrag(group) {
    Object.assign(group, { pointerId: null, item: null, placeholder: null, sourceContainer: null, activeContainer: null, startX: 0, startY: 0, offsetX: 0, offsetY: 0, dragging: false });
}

async function loadClientOffers() {
    const loading = document.getElementById('client-offers-loading');
    loading.hidden = false;
    try {
        const response = await fetch('/bitrix/settings/offers?contextToken=' + encodeURIComponent(contextToken), { cache: 'no-store' });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось загрузить офферы');
        clientOffers = Array.isArray(data) ? data : [];
        clientOffersLoaded = true;
        renderClientOffers();
    } catch (error) {
        showClientSettingsError(error.message);
    } finally {
        loading.hidden = true;
    }
}

function renderClientOffers() {
    const body = document.getElementById('client-offers-body');
    if (!body) return;
    const offers = sortedClientOffers();
    body.innerHTML = offers.map(offer => `<tr>
        <td>${escapeClientSettingsHtml(offer.documentTypeLabel || offer.documentType || '—')}</td>
        <td>${offer.documentId}</td>
        <td>${escapeClientSettingsHtml(offer.documentTitle || '—')}</td>
        <td><span class="client-offer-status ${offer.viewed ? 'is-viewed' : ''}">${offer.viewed ? 'Просмотрен клиентом' : 'Не просмотрен'}</span></td>
        <td><a class="client-offer-open" href="${escapeClientSettingsAttribute(offer.documentUrl || '#')}" target="_blank" rel="noopener noreferrer">Открыть документ</a></td>
    </tr>`).join('') || '<tr><td colspan="5" class="client-offers-empty">Офферов нет</td></tr>';
    document.querySelectorAll('[data-client-offer-sort]').forEach(button => {
        const active = button.dataset.clientOfferSort === clientOfferSort.key;
        button.classList.toggle('is-active', active);
        button.dataset.direction = active ? clientOfferSort.direction : '';
    });
}

function sortedClientOffers() {
    const now = Date.now();
    let offers = [...clientOffers];
    if (clientOfferSort.period === '7') {
        const min = now - 7 * 24 * 60 * 60 * 1000;
        offers = offers.filter(offer => Date.parse(offer.createdAt || '') >= min);
    }
    const key = clientOfferSort.key;
    const direction = clientOfferSort.direction === 'asc' ? 1 : -1;
    offers.sort((a, b) => {
        if (key === 'viewed') return (Number(!!a.viewed) - Number(!!b.viewed)) * direction;
        if (key === 'createdAt') return ((Date.parse(a.createdAt || '') || 0) - (Date.parse(b.createdAt || '') || 0)) * direction;
        return String(a[key] || '').localeCompare(String(b[key] || ''), 'ru', { sensitivity: 'base', numeric: true }) * direction;
    });
    return offers;
}

function changeClientOfferSort(key) {
    if (!key) return;
    if (clientOfferSort.key === key) clientOfferSort.direction = clientOfferSort.direction === 'asc' ? 'desc' : 'asc';
    else { clientOfferSort.key = key; clientOfferSort.direction = 'asc'; }
    renderClientOffers();
}

function setClientSettingsBusy(busy) {
    const sync = document.getElementById('sync-client-users');
    if (sync) sync.disabled = busy;
}
function showClientSettingsError(message) {
    const error = document.getElementById('client-settings-error');
    if (!error) return;
    error.textContent = message || 'Ошибка';
    error.hidden = false;
}
function clientUserSvg() {
    return '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 21a8 8 0 0 0-16 0"></path><circle cx="12" cy="8" r="4"></circle></svg>';
}
function clientGearSvg() {
    return '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h-.1A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.1.36.31.7.6 1 .3.3.68.5 1.1.6h.1v4h-.1c-.42.1-.8.3-1.1.6-.29.3-.5.64-.6 1Z"></path></svg>';
}
function escapeClientSettingsHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function escapeClientSettingsAttribute(value) { return escapeClientSettingsHtml(value).replace(/`/g, '&#96;'); }
