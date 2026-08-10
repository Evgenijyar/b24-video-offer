const mobileContextToken = document.getElementById('mobile-context-token').value;
const entityTypeInput = document.getElementById('entity-type');
const entityPicker = document.getElementById('entity-picker');
const searchInput = document.getElementById('search-query');
const clearSearchButton = document.getElementById('clear-search');
const searchState = document.getElementById('search-state');
const searchResults = document.getElementById('search-results');
const selectedCard = document.getElementById('selected-card');
const selectedTitle = document.getElementById('selected-title');
const selectedMeta = document.getElementById('selected-meta');
const changeSelectionButton = document.getElementById('change-selection');

const recorderSection = document.getElementById('recorder-section');
const cameraPreview = document.getElementById('camera-preview');
const cameraPlaceholder = document.getElementById('camera-placeholder');
const cameraError = document.getElementById('camera-error');
const startCameraButton = document.getElementById('start-camera');
const switchCameraButton = document.getElementById('switch-camera');
const recordToggleButton = document.getElementById('record-toggle');
const recordGlyph = document.getElementById('record-glyph');
const playRecordingButton = document.getElementById('play-recording');
const playGlyph = document.getElementById('play-glyph');
const recordingBadge = document.getElementById('recording-badge');
const recordingTimer = document.getElementById('recording-timer');
const mobileSourceModeInput = document.getElementById('mobile-source-mode');
const mobileSourcePicker = document.getElementById('mobile-source-picker');
const mobileLinkSource = document.getElementById('mobile-link-source');
const mobileCameraSource = document.getElementById('mobile-camera-source');
const mobileFileSource = document.getElementById('mobile-file-source');
const mobileVideoUrl = document.getElementById('mobile-video-url');
const mobileVideoFile = document.getElementById('mobile-video-file');
const mobileChooseFile = document.getElementById('mobile-choose-file');
const mobileSelectedFile = document.getElementById('mobile-selected-file');
const mobileSelectedFileName = document.getElementById('mobile-selected-file-name');
const mobileSelectedFileMeta = document.getElementById('mobile-selected-file-meta');
const mobileReplaceFile = document.getElementById('mobile-replace-file');
const mobileFilePreview = document.getElementById('mobile-file-preview');
const fileError = document.getElementById('file-error');
const uploadProcessing = document.getElementById('upload-processing');
const uploadStatus = document.getElementById('upload-status');
const uploadBytes = document.getElementById('upload-bytes');

const offerSection = document.getElementById('offer-section');
const goalInput = document.getElementById('view-notification-goal');
const goalPicker = document.getElementById('goal-picker');
const form = document.getElementById('mobile-offer-form');
const submitButton = document.getElementById('submit-button');
const formError = document.getElementById('form-error');
const processing = document.getElementById('processing');
const processingStatus = document.getElementById('processing-status');
const processingPercent = document.getElementById('processing-percent');
const progressBar = document.getElementById('progress-bar');
const deliveryStatus = document.getElementById('delivery-status');
const readyResult = document.getElementById('ready-result');
const readyMessage = document.getElementById('ready-message');
const publicLink = document.getElementById('public-link');

const permissionDialog = document.getElementById('permission-dialog');
const permissionMessage = document.getElementById('permission-message');
const openPermissionsButton = document.getElementById('open-permissions');
const retryPermissionsButton = document.getElementById('retry-permissions');
const closePermissionsButton = document.getElementById('close-permissions');
const permissionFallback = document.getElementById('permission-fallback');

const isBitrixMobile = /BitrixMobile/i.test(navigator.userAgent || '');
const MAX_MANUAL_FILE_BYTES = 100 * 1024 * 1024;
const MANUAL_FILE_CHUNK_BYTES = 4 * 1024 * 1024;
const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 60_000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90_000;

let debounceTimer = null;
let activeSearchController = null;
let searchSequence = 0;
let selectedEntity = null;

let cameraStream = null;
let mediaRecorder = null;
let facingMode = 'user';
let recordingStartedAt = null;
let timerHandle = null;
let recordedPreviewUrl = null;
let permissionReturnPending = false;
let permissionRetryInFlight = false;
let wakeLock = null;
let uploadSession = null;
let uploadFailure = null;
let uploadQueue = [];
let activeChunkUploads = 0;
let nextChunkSequence = 0;
let uploadedChunkCount = 0;
let uploadedChunkBytes = 0;
let enqueuedChunkBytes = 0;
let uploadExpectedBytes = 0;
let activeChunkProgress = new Map();
let uploadDrainWaiters = [];
let activeUploadControllers = new Set();
let finalChunkCount = 0;
let uploadGeneration = 0;
let recordingFinalizing = false;
let normalizationPollTimer = null;
let activeOfferId = null;
let offerPollTimer = null;

initializeBitrixFrame();
initializeEntityPicker();
initializeGoalPicker();
initializeMobileSourcePicker();
initializeRecorder();
reportClientEvent('CAPABILITIES', JSON.stringify({
    mediaDevices: !!navigator.mediaDevices,
    getUserMedia: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
    mediaRecorder: typeof window.MediaRecorder !== 'undefined',
    mp4: recorderTypeSupported('video/mp4'),
    webm: recorderTypeSupported('video/webm')
}));

searchInput.addEventListener('input', () => {
    clearSearchButton.hidden = !searchInput.value;
    scheduleSearch();
});

clearSearchButton.addEventListener('click', () => {
    searchInput.value = '';
    clearSearchButton.hidden = true;
    resetSearchOutput('Начните вводить название, имя, ID или телефон.');
    resetSelection();
});

changeSelectionButton.addEventListener('click', async () => {
    await resetSelection();
    searchInput.scrollIntoView({behavior: 'smooth', block: 'center'});
});

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!selectedEntity || !selectedEntity.contextToken) {
        setFormError('Сначала выберите карточку Bitrix24.');
        return;
    }

    const sourceMode = mobileSourceModeInput.value;
    if (sourceMode === 'LINK') {
        const url = mobileVideoUrl.value.trim();
        if (!url) {
            setFormError('Вставьте ссылку на видео.');
            mobileVideoUrl.focus();
            return;
        }
        try {
            new URL(url);
        } catch (_) {
            setFormError('Введите корректную ссылку на видео.');
            mobileVideoUrl.focus();
            return;
        }
    } else if (!uploadSession || uploadSession.status !== 'READY') {
        setFormError(sourceMode === 'FILE'
            ? 'Сначала выберите и дождитесь подготовки видеофайла.'
            : 'Сначала запишите и дождитесь сохранения видео.');
        return;
    }

    clearInterval(offerPollTimer);
    setFormError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(sourceMode === 'LINK' ? 2 : 100, sourceMode === 'LINK' ? 'Получаем видео по ссылке…' : 'Создаём видеооффер…');
    deliveryStatus.textContent = sourceMode === 'LINK'
        ? 'Источник определяется автоматически…'
        : 'Добавляем ссылку в выбранную карточку Bitrix24…';

    try {
        let response;
        if (sourceMode === 'LINK') {
            response = await fetch('/bitrix/video-offers', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    contextToken: selectedEntity.contextToken,
                    recordingUrl: mobileVideoUrl.value.trim(),
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    viewNotificationGoal: goalInput.value
                })
            });
        } else {
            response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/offer`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    uploadToken: uploadSession.uploadToken,
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    viewNotificationGoal: goalInput.value
                })
            });
        }
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось создать видеооффер');

        activeOfferId = data.id;
        renderOffer(data);
        if (data.bitrixDeliveryStatus === 'PENDING' || data.bitrixDeliveryStatus === 'SENDING'
            || data.status === 'QUEUED' || data.status === 'PREPARING') {
            offerPollTimer = setInterval(checkOfferStatus, 1000);
        } else {
            submitButton.disabled = false;
            submitButton.textContent = 'Создать ещё один оффер';
        }
    } catch (error) {
        finishOfferWithError(error.message || 'Не удалось создать видеооффер');
    }
});

function initializeEntityPicker() {
    entityPicker.querySelectorAll('[data-entity-type]').forEach((button) => {
        button.addEventListener('click', async () => {
            const nextType = button.dataset.entityType;
            if (!nextType || nextType === entityTypeInput.value) return;
            entityTypeInput.value = nextType;
            entityPicker.querySelectorAll('[data-entity-type]').forEach((item) => {
                const active = item === button;
                item.classList.toggle('is-active', active);
                item.setAttribute('aria-pressed', active ? 'true' : 'false');
            });
            await resetSelection();
            scheduleSearch(true);
        });
    });
}

function initializeGoalPicker() {
    goalPicker.querySelectorAll('[data-goal]').forEach((button) => {
        button.addEventListener('click', () => {
            goalInput.value = button.dataset.goal;
            goalPicker.querySelectorAll('[data-goal]').forEach((item) => {
                const active = item === button;
                item.classList.toggle('is-active', active);
                item.setAttribute('aria-checked', active ? 'true' : 'false');
            });
        });
    });
}

function initializeMobileSourcePicker() {
    mobileSourcePicker.querySelectorAll('[data-mobile-source]').forEach((button) => {
        button.addEventListener('click', async () => {
            const mode = button.dataset.mobileSource;
            if (!mode || mode === mobileSourceModeInput.value || isRecordingActive() || recordingFinalizing) return;
            await switchMobileSource(mode);
        });
    });
    mobileVideoUrl.addEventListener('input', () => setFormError(null));
    mobileChooseFile.addEventListener('click', () => mobileVideoFile.click());
    mobileReplaceFile.addEventListener('click', () => mobileVideoFile.click());
    mobileVideoFile.addEventListener('change', async () => {
        const file = mobileVideoFile.files && mobileVideoFile.files[0];
        mobileVideoFile.value = '';
        if (file) await uploadManualFile(file);
    });
}

async function switchMobileSource(mode) {
    const normalized = ['LINK', 'CAMERA', 'FILE'].includes(mode) ? mode : 'CAMERA';
    await resetRecordingState(false);
    mobileSourceModeInput.value = normalized;
    mobileSourcePicker.querySelectorAll('[data-mobile-source]').forEach((button) => {
        const active = button.dataset.mobileSource === normalized;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    mobileLinkSource.hidden = normalized !== 'LINK';
    mobileCameraSource.hidden = normalized !== 'CAMERA';
    mobileFileSource.hidden = normalized !== 'FILE';
    clearFileUi();
    offerSection.hidden = normalized !== 'LINK';
    if (normalized === 'CAMERA' && selectedEntity && supportsEmbeddedRecording()) {
        setTimeout(() => startCamera(false), 100);
    } else if (normalized === 'LINK') {
        setTimeout(() => mobileVideoUrl.focus(), 80);
    }
    fitWindow();
}

function initializeRecorder() {
    startCameraButton.addEventListener('click', () => startCamera(false));
    switchCameraButton.addEventListener('click', switchCamera);
    recordToggleButton.addEventListener('click', toggleRecording);
    playRecordingButton.addEventListener('click', toggleRecordedPlayback);

    openPermissionsButton.addEventListener('click', openBitrixAndroidSettings);
    retryPermissionsButton.addEventListener('click', retryPermissionsNow);
    closePermissionsButton.addEventListener('click', hidePermissionDialog);

    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && mobileSourceModeInput.value === 'CAMERA') retryCameraAfterPermissionReturn();
    });
    window.addEventListener('focus', () => {
        if (mobileSourceModeInput.value === 'CAMERA') retryCameraAfterPermissionReturn();
    });
    window.addEventListener('pageshow', () => {
        if (mobileSourceModeInput.value === 'CAMERA') retryCameraAfterPermissionReturn();
    });
    document.addEventListener('resume', () => {
        if (mobileSourceModeInput.value === 'CAMERA') retryCameraAfterPermissionReturn();
    }, false);

    cameraPreview.addEventListener('play', updatePlayButtonState);
    cameraPreview.addEventListener('pause', updatePlayButtonState);
    cameraPreview.addEventListener('ended', updatePlayButtonState);

    if (!supportsEmbeddedRecording()) {
        startCameraButton.hidden = true;
        switchCameraButton.hidden = true;
        recordToggleButton.hidden = true;
        playRecordingButton.hidden = true;
        setCameraError('Встроенная камера недоступна в этом WebView. Используйте режим «Файл».');
    }
}


function scheduleSearch(immediate = false) {
    clearTimeout(debounceTimer);
    const query = searchInput.value.trim();

    if (query.length < 2) {
        resetSearchOutput(query.length === 0
            ? 'Начните вводить название, имя, ID или телефон.'
            : 'Введите ещё один символ.');
        return;
    }

    debounceTimer = setTimeout(runSearch, immediate ? 0 : 350);
}

async function runSearch() {
    const query = searchInput.value.trim();
    if (query.length < 2) return;

    if (activeSearchController) activeSearchController.abort();
    activeSearchController = new AbortController();
    const sequence = ++searchSequence;

    setSearchState('Ищем в Bitrix24…', true);
    searchResults.hidden = true;

    const params = new URLSearchParams({
        mobileContextToken,
        entityType: entityTypeInput.value,
        q: query
    });

    try {
        const response = await fetch('/bitrix/mobile/search?' + params.toString(), {
            cache: 'no-store',
            signal: activeSearchController.signal
        });
        const data = await readJson(response);
        if (sequence !== searchSequence) return;
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось выполнить поиск');
        }
        renderSearchResults(data.results || []);
    } catch (error) {
        if (error.name === 'AbortError') return;
        setSearchState(error.message || 'Не удалось выполнить поиск', false);
        searchResults.hidden = true;
    }
}

function renderSearchResults(results) {
    searchResults.replaceChildren();
    if (!results.length) {
        setSearchState('Ничего не найдено. Проверьте запрос или выберите другой тип сущности.', false);
        searchResults.hidden = true;
        return;
    }

    searchState.hidden = true;
    searchResults.hidden = false;

    for (const item of results) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'search-result';

        const badge = document.createElement('span');
        badge.className = 'result-type';
        badge.textContent = typeShort(item.entityType);

        const copy = document.createElement('span');
        copy.className = 'result-copy';

        const title = document.createElement('span');
        title.className = 'result-title';
        title.textContent = item.title;

        const meta = document.createElement('span');
        meta.className = 'result-meta';
        meta.textContent = '#' + item.id + (item.subtitle ? ' · ' + item.subtitle : '');

        const arrow = document.createElement('span');
        arrow.className = 'result-arrow';
        arrow.textContent = '›';

        copy.append(title, meta);
        button.append(badge, copy, arrow);
        button.addEventListener('click', () => selectEntity(item));
        searchResults.append(button);
    }
    fitWindow();
}

async function selectEntity(item) {
    await resetRecordingState(false);
    selectedEntity = item;
    selectedTitle.textContent = item.title;
    selectedMeta.textContent = typeLabel(item.entityType) + ' №' + item.id
        + (item.subtitle ? ' · ' + item.subtitle : '');
    selectedCard.hidden = false;
    recorderSection.hidden = false;
    searchResults.hidden = true;
    searchState.hidden = true;
    clearCameraMessages();
    clearFileUi();

    const mode = mobileSourceModeInput.value || 'CAMERA';
    mobileLinkSource.hidden = mode !== 'LINK';
    mobileCameraSource.hidden = mode !== 'CAMERA';
    mobileFileSource.hidden = mode !== 'FILE';
    offerSection.hidden = mode !== 'LINK';
    recorderSection.scrollIntoView({behavior: 'smooth', block: 'start'});
    fitWindow();

    if (mode === 'CAMERA' && supportsEmbeddedRecording()) {
        setTimeout(() => startCamera(false), 120);
    }
}


async function resetSelection() {
    selectedEntity = null;
    selectedCard.hidden = true;
    recorderSection.hidden = true;
    await resetRecordingState(false);
    clearFileUi();
}


function resetSearchOutput(message) {
    if (activeSearchController) activeSearchController.abort();
    searchResults.replaceChildren();
    searchResults.hidden = true;
    setSearchState(message, false);
}

function setSearchState(message, loading) {
    searchState.hidden = false;
    searchState.textContent = message;
    searchState.classList.toggle('is-loading', loading);
    fitWindow();
}

async function startCamera(switching) {
    if (!selectedEntity || mobileSourceModeInput.value !== 'CAMERA') return false;
    if (!supportsEmbeddedRecording()) {
        setCameraError('Этот WebView не предоставляет доступ к встроенной камере. Используйте системную камеру.');
        return false;
    }

    clearCameraMessages();
    hidePermissionDialog();
    startCameraButton.disabled = true;
    startCameraButton.textContent = switching ? 'Переключаем…' : 'Включаем…';

    try {
        const permissionStates = await queryMediaPermissionStates();
        if (permissionStates.camera === 'denied' || permissionStates.microphone === 'denied') {
            showPermissionDialog('Для записи Bitrix24 нужен доступ к камере и микрофону. Разрешите оба доступа в настройках приложения.');
            return false;
        }

        stopCameraStream();
        clearRecordedPreview();
        const constraints = {
            video: {
                facingMode: {ideal: facingMode},
                width: {ideal: 1280, max: 1280},
                height: {ideal: 720, max: 1280},
                frameRate: {ideal: 30, max: 30}
            },
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                channelCount: 1
            }
        };

        cameraStream = await navigator.mediaDevices.getUserMedia(constraints);
        cameraPreview.removeAttribute('src');
        cameraPreview.srcObject = cameraStream;
        cameraPreview.muted = true;
        cameraPreview.autoplay = true;
        cameraPreview.playsInline = true;
        cameraPreview.hidden = false;
        await cameraPreview.play().catch(() => {});
        cameraPlaceholder.hidden = true;
        startCameraButton.hidden = true;
        switchCameraButton.hidden = false;
        recordToggleButton.hidden = false;
        playRecordingButton.hidden = true;
        updateRecordButtonState(false);
        permissionReturnPending = false;
        try { sessionStorage.removeItem('b24-awaiting-media-permission'); } catch (_) {}
        const videoTrack = cameraStream.getVideoTracks()[0];
        let cameraDetails = facingMode;
        try {
            const settings = videoTrack && typeof videoTrack.getSettings === 'function' ? videoTrack.getSettings() : {};
            cameraDetails += ';settings=' + JSON.stringify({
                width: settings.width || null,
                height: settings.height || null,
                frameRate: settings.frameRate || null,
                facingMode: settings.facingMode || null
            });
        } catch (_) { }
        reportClientEvent('CAMERA_READY', cameraDetails);
        return true;
    } catch (error) {
        stopCameraStream();
        startCameraButton.hidden = false;
        switchCameraButton.hidden = true;
        recordToggleButton.hidden = true;
        playRecordingButton.hidden = !!recordedPreviewUrl;
        const message = cameraPermissionMessage(error);
        setCameraError(message);
        const permissionRelated = await isPermissionRelatedCameraError(error);
        if (permissionRelated) {
            showPermissionDialog(message);
        }
        reportClientEvent('CAMERA_ERROR', (error && error.name ? error.name : 'Error') + ': ' + (error && error.message ? error.message : message));
        return false;
    } finally {
        startCameraButton.disabled = false;
        startCameraButton.textContent = 'Включить камеру';
    }
}

async function switchCamera() {
    if (isRecordingActive() || recordingFinalizing) return;
    facingMode = facingMode === 'user' ? 'environment' : 'user';
    switchCameraButton.disabled = true;
    try {
        await startCamera(true);
    } finally {
        switchCameraButton.disabled = false;
    }
}

async function toggleRecording() {
    if (recordingFinalizing) return;
    if (isRecordingActive()) {
        stopRecording();
        return;
    }

    if (uploadSession && (uploadSession.status === 'READY' || uploadSession.status === 'ERROR')) {
        await discardCurrentUpload();
    }

    if (!cameraStream) {
        const cameraStarted = await startCamera(false);
        if (!cameraStarted) return;
    }
    await startRecording();
}

async function startRecording() {
    if (!selectedEntity || !cameraStream || isRecordingActive()) return;

    clearCameraMessages();
    hidePermissionDialog();
    resetOfferOutput();
    uploadProcessing.hidden = true;
    resetChunkUploader();
    recordingFinalizing = false;

    try {
        const mimeType = chooseRecorderMimeType();
        const recorderOptions = {
            videoBitsPerSecond: 1_800_000,
            audioBitsPerSecond: 96_000
        };
        if (mimeType) recorderOptions.mimeType = mimeType;

        try {
            mediaRecorder = new MediaRecorder(cameraStream, recorderOptions);
        } catch (_) {
            mediaRecorder = new MediaRecorder(cameraStream);
        }

        uploadSession = await createUploadSession(mediaRecorder.mimeType || mimeType || 'video/webm');
        mediaRecorder.addEventListener('dataavailable', handleRecordedChunk);
        mediaRecorder.addEventListener('error', handleRecorderError);
        mediaRecorder.addEventListener('stop', finalizeRecordedVideo, {once: true});

        await new Promise((resolve, reject) => {
            const started = () => resolve();
            const failed = (event) => reject(event && event.error ? event.error : new Error('MediaRecorder не начал запись'));
            mediaRecorder.addEventListener('start', started, {once: true});
            mediaRecorder.addEventListener('error', failed, {once: true});
            try {
                mediaRecorder.start(MEDIA_CHUNK_INTERVAL_MS);
            } catch (error) {
                reject(error);
            }
        });

        startTimer();
        await acquireWakeLock();
        reportClientEvent('RECORDING_STARTED', JSON.stringify({
            mimeType: mediaRecorder.mimeType || mimeType || 'default',
            videoBitsPerSecond: mediaRecorder.videoBitsPerSecond || null,
            audioBitsPerSecond: mediaRecorder.audioBitsPerSecond || null
        }));
        switchCameraButton.disabled = true;
        playRecordingButton.hidden = true;
        recordingBadge.hidden = false;
        updateRecordButtonState(true);
    } catch (error) {
        stopTimer();
        mediaRecorder = null;
        updateRecordButtonState(false);
        setCameraError(error.message || 'Не удалось начать запись');
        if (uploadSession) {
            await discardCurrentUpload();
        }
    }
}

function handleRecorderError(event) {
    uploadFailure = event && event.error ? event.error : new Error('Ошибка MediaRecorder');
    reportClientEvent('RECORDER_ERROR', uploadFailure.name + ': ' + (uploadFailure.message || ''));
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        try { mediaRecorder.stop(); } catch (_) {}
    }
}

function handleRecordedChunk(event) {
    if (!event.data || event.data.size <= 0 || !uploadSession) return;
    enqueueChunkUpload(event.data);
}

function enqueueChunkUpload(blob) {
    const sequence = nextChunkSequence++;
    enqueuedChunkBytes += blob.size;
    uploadQueue.push({
        sequence,
        blob,
        generation: uploadGeneration,
        session: uploadSession
    });
    pumpChunkUploads();
}

function pumpChunkUploads() {
    while (!uploadFailure && activeChunkUploads < MAX_PARALLEL_UPLOADS && uploadQueue.length > 0) {
        const task = uploadQueue.shift();
        activeChunkUploads++;
        uploadChunkWithRetry(task.blob, task.sequence, task.session)
            .then((data) => {
                if (task.generation !== uploadGeneration) return;
                activeChunkProgress.delete(task.sequence);
                uploadedChunkCount++;
                uploadedChunkBytes += task.blob.size;
                if (uploadSession && task.session && uploadSession.id === task.session.id && data) {
                    uploadSession = {
                        ...uploadSession,
                        status: data.status || uploadSession.status,
                        bytesReceived: Math.max(
                            Number(uploadSession.bytesReceived || 0),
                            Number(data.bytesReceived || 0),
                            uploadedChunkBytes)
                    };
                }
                renderUploadSaveProgress();
            })
            .catch((error) => {
                if (task.generation !== uploadGeneration) return;
                activeChunkProgress.delete(task.sequence);
                if (!uploadFailure) {
                    uploadFailure = error;
                    setCameraError('Не удалось сохранить запись: ' + (error.message || 'ошибка сети'));
                    reportClientEvent('UPLOAD_CHUNK_ERROR', 'sequence=' + task.sequence + ';' + (error.message || 'network error'));
                    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
                        try { mediaRecorder.stop(); } catch (_) { }
                    }
                }
            })
            .finally(() => {
                if (task.generation !== uploadGeneration) return;
                activeChunkUploads = Math.max(0, activeChunkUploads - 1);
                pumpChunkUploads();
                notifyUploadDrainWaiters();
            });
    }
    notifyUploadDrainWaiters();
}

function stopRecording() {
    if (!isRecordingActive() || recordingFinalizing) return;
    recordingFinalizing = true;
    recordToggleButton.disabled = true;
    stopTimer();
    updateRecordButtonState(false);
    uploadProcessing.hidden = false;
    uploadStatus.textContent = 'Сохраняем видео…';
    renderUploadSaveProgress();
    try {
        mediaRecorder.stop();
    } catch (error) {
        recordingFinalizing = false;
        recordToggleButton.disabled = false;
        updateRecordButtonState(true);
        setCameraError(error.message || 'Не удалось остановить запись');
    }
}

async function finalizeRecordedVideo() {
    stopTimer();
    await releaseWakeLock();
    recordingBadge.hidden = true;
    switchCameraButton.disabled = false;
    recordToggleButton.disabled = true;
    stopCameraStream();
    finalChunkCount = nextChunkSequence;
    reportClientEvent('RECORDING_STOPPED', JSON.stringify({
        chunks: finalChunkCount,
        alreadyUploaded: uploadedChunkCount,
        queued: uploadQueue.length,
        active: activeChunkUploads
    }));
    renderUploadSaveProgress();

    try {
        await waitForChunkUploads(UPLOAD_DRAIN_TIMEOUT_MS);
        if (uploadFailure) throw uploadFailure;
        if (finalChunkCount <= 0) throw new Error('Запись не содержит видеоданных');
        setUploadProgress(100);
        uploadStatus.textContent = 'Подготавливаем видео…';
        reportClientEvent('RECORDING_UPLOAD_DRAINED', JSON.stringify({
            chunks: finalChunkCount,
            bytes: uploadedChunkBytes
        }));
        uploadSession = await completeUploadSession(finalChunkCount);
        await waitForNormalization();
    } catch (error) {
        setCameraError(error.message || 'Не удалось сохранить видео');
        uploadProcessing.hidden = true;
        startCameraButton.hidden = false;
        recordToggleButton.hidden = false;
        playRecordingButton.hidden = true;
        updateRecordButtonState(false);
    } finally {
        mediaRecorder = null;
        recordingFinalizing = false;
        recordToggleButton.disabled = false;
    }
}

async function uploadManualFile(file) {
    if (!selectedEntity || mobileSourceModeInput.value !== 'FILE') return;
    setFileError(null);
    try {
        validateManualVideoFile(file);
    } catch (error) {
        setFileError(error.message || 'Неподдерживаемый видеофайл');
        return;
    }

    await resetRecordingState(false);
    recorderSection.hidden = false;
    mobileFileSource.hidden = false;
    mobileCameraSource.hidden = true;
    mobileLinkSource.hidden = true;
    clearCameraMessages();
    stopCameraStream();
    clearRecordedPreview();
    clearFilePreview();

    mobileSelectedFile.hidden = false;
    mobileSelectedFileName.textContent = file.name;
    mobileSelectedFileMeta.textContent = `${formatBytes(file.size)} · ${file.type || fileExtension(file.name).toUpperCase()}`;
    mobileChooseFile.hidden = true;
    uploadProcessing.hidden = false;
    uploadStatus.textContent = 'Подготавливаем загрузку…';
    setUploadProgress(1);
    uploadBytes.textContent = `0 Б из ${formatBytes(file.size)}`;
    offerSection.hidden = true;

    try {
        uploadSession = await createUploadSession(file.type || mimeFromFileName(file.name), 'FILE', file.size);
        resetChunkUploader();
        uploadExpectedBytes = file.size;
        setUploadProgress(2);
        uploadStatus.textContent = 'Загрузка началась…';
        let offset = 0;
        while (offset < file.size) {
            const blob = file.slice(offset, Math.min(file.size, offset + MANUAL_FILE_CHUNK_BYTES));
            enqueueChunkUpload(blob);
            offset += blob.size;
        }
        finalChunkCount = nextChunkSequence;
        renderUploadSaveProgress();
        await waitForChunkUploads(Math.max(UPLOAD_DRAIN_TIMEOUT_MS, 5 * 60 * 1000));
        if (uploadFailure) throw uploadFailure;
        setUploadProgress(100);
        uploadStatus.textContent = 'Подготавливаем видео…';
        uploadSession = await completeUploadSession(finalChunkCount);
        await waitForNormalization();
    } catch (error) {
        setFileError(error.message || 'Не удалось загрузить видео');
        uploadProcessing.hidden = true;
        mobileChooseFile.hidden = false;
    }
    fitWindow();
}


async function createUploadSession(mimeType, sourceKind = 'RECORDING', declaredSizeBytes = null) {
    if (!selectedEntity || !selectedEntity.contextToken) {
        throw new Error('Не выбрана карточка Bitrix24');
    }
    const response = await fetch('/bitrix/mobile/uploads', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            contextToken: selectedEntity.contextToken,
            mimeType: mimeType || 'application/octet-stream',
            sourceKind,
            declaredSizeBytes
        })
    });
    const data = await readJson(response);
    if (!response.ok) {
        throw new Error(data.message || 'Не удалось создать сессию загрузки');
    }
    return data;
}


async function uploadChunkWithRetry(blob, sequence, session) {
    let lastError = null;
    for (let attempt = 1; attempt <= 4; attempt++) {
        activeChunkProgress.set(sequence, 0);
        renderUploadSaveProgress();
        try {
            return await uploadChunkOnce(blob, sequence, session);
        } catch (error) {
            activeChunkProgress.set(sequence, 0);
            renderUploadSaveProgress();
            lastError = error;
            if (attempt < 4) await sleep(Math.min(1600, attempt * 400));
        }
    }
    throw lastError || new Error('Не удалось загрузить часть видео');
}

function uploadChunkOnce(blob, sequence, session) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        activeUploadControllers.add(xhr);
        xhr.open('PUT', `/bitrix/mobile/uploads/${encodeURIComponent(session.id)}/chunks/${sequence}`, true);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.setRequestHeader('X-Upload-Token', session.uploadToken);
        xhr.timeout = CHUNK_UPLOAD_TIMEOUT_MS;
        xhr.upload.onprogress = (event) => {
            activeChunkProgress.set(sequence, Math.min(blob.size, Math.max(0, Number(event.loaded) || 0)));
            renderUploadSaveProgress();
        };
        xhr.onload = () => {
            activeUploadControllers.delete(xhr);
            const data = parseJsonText(xhr.responseText);
            if (xhr.status >= 200 && xhr.status < 300) resolve(data);
            else reject(new Error(data.message || 'Сервер не принял часть видео'));
        };
        xhr.onerror = () => {
            activeUploadControllers.delete(xhr);
            reject(new Error('Ошибка сети при загрузке видео'));
        };
        xhr.ontimeout = () => {
            activeUploadControllers.delete(xhr);
            reject(new Error('Сервер слишком долго принимал видео'));
        };
        xhr.onabort = () => {
            activeUploadControllers.delete(xhr);
            reject(new Error('Загрузка видео отменена'));
        };
        xhr.send(blob);
    });
}

async function completeUploadSession(chunkCount) {
    const response = await fetchWithTimeout(
        `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/complete?chunkCount=${encodeURIComponent(chunkCount)}`,
        {
            method: 'POST',
            headers: {'X-Upload-Token': uploadSession.uploadToken}
        },
        60_000);
    const data = await readJson(response);
    if (!response.ok) {
        throw new Error(data.message || 'Не удалось завершить загрузку видео');
    }
    return data;
}

async function waitForNormalization() {
    clearInterval(normalizationPollTimer);
    const startedAt = Date.now();

    while (Date.now() - startedAt < 20 * 60 * 1000) {
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}?uploadToken=${encodeURIComponent(uploadSession.uploadToken)}`,
            {cache: 'no-store'},
            10_000);
        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось проверить обработку видео');
        }
        uploadSession = data;
        uploadBytes.textContent = formatBytes(data.bytesReceived);

        if (data.status === 'READY') {
            showNormalizedVideo(data);
            return;
        }
        if (data.status === 'ERROR') {
            throw new Error(data.errorMessage || 'Не удалось обработать видео');
        }
        uploadStatus.textContent = 'Подготавливаем видео…';
        const elapsed = Date.now() - startedAt;
        await sleep(elapsed < 8000 ? 250 : 750);
    }
    throw new Error('Обработка видео заняла слишком много времени');
}

function showNormalizedVideo(data) {
    uploadProcessing.hidden = true;
    stopCameraStream();
    recordedPreviewUrl = data.previewUrl || '';

    if (mobileSourceModeInput.value === 'FILE') {
        clearFilePreview();
        mobileFilePreview.src = recordedPreviewUrl;
        mobileFilePreview.hidden = !recordedPreviewUrl;
        mobileFilePreview.load();
        mobileChooseFile.hidden = true;
        mobileSelectedFile.hidden = false;
    } else {
        cameraPlaceholder.hidden = true;
        cameraPreview.hidden = false;
        cameraPreview.autoplay = false;
        cameraPreview.muted = false;
        cameraPreview.srcObject = null;
        cameraPreview.src = recordedPreviewUrl;
        cameraPreview.load();
        switchCameraButton.hidden = true;
        switchCameraButton.disabled = false;
        startCameraButton.hidden = true;
        recordToggleButton.hidden = false;
        recordToggleButton.disabled = false;
        updateRecordButtonState(false);
        playRecordingButton.hidden = !recordedPreviewUrl;
        updatePlayButtonState();
    }

    offerSection.hidden = false;
    setCameraError(null);
    setFileError(null);
    reportClientEvent('UPLOAD_READY', 'bytes=' + Number(data.bytesReceived || 0) + ';source=' + (data.sourceKind || mobileSourceModeInput.value));
    fitWindow();
}


async function resetRecordingState(showCameraButton) {
    stopTimer(true);
    clearInterval(normalizationPollTimer);
    clearInterval(offerPollTimer);
    hidePermissionDialog();

    const uploadToDiscard = uploadSession;
    if (mediaRecorder) {
        mediaRecorder.removeEventListener('dataavailable', handleRecordedChunk);
        mediaRecorder.removeEventListener('error', handleRecorderError);
        mediaRecorder.removeEventListener('stop', finalizeRecordedVideo);
        if (mediaRecorder.state !== 'inactive') {
            try { mediaRecorder.stop(); } catch (_) {}
        }
    }
    mediaRecorder = null;
    recordingFinalizing = false;
    stopCameraStream();
    await releaseWakeLock();
    uploadSession = null;
    resetChunkUploader();
    clearRecordedPreview();
    clearFilePreview();
    offerSection.hidden = true;
    processing.hidden = true;
    readyResult.hidden = true;
    uploadProcessing.hidden = true;
    recordingBadge.hidden = true;
    switchCameraButton.hidden = true;
    switchCameraButton.disabled = false;
    recordToggleButton.hidden = true;
    recordToggleButton.disabled = false;
    updateRecordButtonState(false);
    playRecordingButton.hidden = true;
    startCameraButton.hidden = !showCameraButton || !supportsEmbeddedRecording();
    cameraPlaceholder.hidden = false;
    clearCameraMessages();
    resetOfferOutput();

    if (uploadToDiscard && uploadToDiscard.status !== 'CONSUMED') {
        deleteUploadSession(uploadToDiscard).catch(() => {});
    }
}

async function discardCurrentUpload() {
    const previous = uploadSession;
    uploadSession = null;
    resetChunkUploader();
    uploadProcessing.hidden = true;
    offerSection.hidden = true;
    resetOfferOutput();
    clearRecordedPreview();
    if (previous && previous.status !== 'CONSUMED') {
        await deleteUploadSession(previous).catch(() => {});
    }
}

async function deleteUploadSession(session) {
    if (!session || !session.id || !session.uploadToken) return;
    const response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(session.id)}`, {
        method: 'DELETE',
        headers: {'X-Upload-Token': session.uploadToken}
    });
    if (!response.ok && response.status !== 404 && response.status !== 409) {
        const data = await readJson(response);
        throw new Error(data.message || 'Не удалось удалить предыдущую запись');
    }
}

function resetChunkUploader() {
    uploadGeneration++;
    uploadFailure = null;
    uploadQueue = [];
    activeChunkUploads = 0;
    nextChunkSequence = 0;
    uploadedChunkCount = 0;
    uploadedChunkBytes = 0;
    enqueuedChunkBytes = 0;
    uploadExpectedBytes = 0;
    activeChunkProgress.clear();
    finalChunkCount = 0;
    uploadDrainWaiters.splice(0).forEach((waiter) => waiter.resolve());
    activeUploadControllers.forEach((controller) => {
        try { controller.abort(); } catch (_) { }
    });
    activeUploadControllers.clear();
    setUploadProgress(0);
}

function waitForChunkUploads(timeoutMs) {
    if (uploadFailure) return Promise.reject(uploadFailure);
    if (uploadQueue.length === 0 && activeChunkUploads === 0) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const waiter = {resolve, reject, timeout: null};
        waiter.timeout = setTimeout(() => {
            const index = uploadDrainWaiters.indexOf(waiter);
            if (index >= 0) uploadDrainWaiters.splice(index, 1);
            reject(new Error('Не удалось вовремя передать видео на сервер. Проверьте соединение и повторите запись.'));
        }, timeoutMs);
        uploadDrainWaiters.push(waiter);
    });
}

function notifyUploadDrainWaiters() {
    if (uploadFailure) {
        const error = uploadFailure;
        const waiters = uploadDrainWaiters.splice(0);
        waiters.forEach((waiter) => {
            clearTimeout(waiter.timeout);
            waiter.reject(error);
        });
        return;
    }
    if (uploadQueue.length !== 0 || activeChunkUploads !== 0) return;
    const waiters = uploadDrainWaiters.splice(0);
    waiters.forEach((waiter) => {
        clearTimeout(waiter.timeout);
        waiter.resolve();
    });
}

function renderUploadSaveProgress() {
    if (uploadProcessing.hidden) return;
    const inFlightBytes = [...activeChunkProgress.values()]
        .reduce((sum, value) => sum + Math.max(0, Number(value) || 0), 0);
    const sentBytes = uploadedChunkBytes + inFlightBytes;
    const totalBytes = Math.max(uploadExpectedBytes || 0, enqueuedChunkBytes || 0, 1);
    const percent = Math.max(0, Math.min(99, Math.round(sentBytes * 100 / totalBytes)));
    setUploadProgress(percent);
    const verb = mobileSourceModeInput.value === 'FILE' ? 'Загружаем видео' : 'Сохраняем видео';
    uploadStatus.textContent = `${verb} ${percent}%`;
    uploadBytes.textContent = `${formatBytes(Math.min(sentBytes, totalBytes))} из ${formatBytes(totalBytes)}`;
}

function setUploadProgress(percent) {
    const normalized = Math.max(0, Math.min(100, Number(percent) || 0));
    const bar = document.getElementById('upload-save-progress-bar');
    if (bar) bar.style.width = normalized + '%';
}

async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController();
    const timeoutHandle = setTimeout(() => controller.abort(), timeoutMs);
    try {
        return await fetch(url, {...(options || {}), signal: controller.signal});
    } catch (error) {
        if (error && error.name === 'AbortError') {
            throw new Error('Сервер слишком долго не отвечал');
        }
        throw error;
    } finally {
        clearTimeout(timeoutHandle);
    }
}

function validateManualVideoFile(file) {
    if (!file || file.size <= 0) throw new Error('Выбран пустой видеофайл.');
    if (file.size > MAX_MANUAL_FILE_BYTES) throw new Error('Размер видеофайла не должен превышать 100 МБ.');
    const extension = fileExtension(file.name);
    if (!['mp4', 'mov', 'webm', 'mkv', 'm4v'].includes(extension)) {
        throw new Error('Поддерживаются MP4, MOV, WebM, MKV и M4V.');
    }
}

function fileExtension(name) {
    const value = String(name || '');
    const index = value.lastIndexOf('.');
    return index >= 0 ? value.substring(index + 1).toLowerCase() : '';
}

function mimeFromFileName(name) {
    return ({mp4:'video/mp4', mov:'video/quicktime', webm:'video/webm', mkv:'video/x-matroska', m4v:'video/x-m4v'})[fileExtension(name)]
        || 'application/octet-stream';
}

function setFileError(message) {
    fileError.hidden = !message;
    fileError.textContent = message || '';
}

function clearFilePreview() {
    try { mobileFilePreview.pause(); } catch (_) {}
    mobileFilePreview.removeAttribute('src');
    mobileFilePreview.hidden = true;
}

function clearFileUi() {
    clearFilePreview();
    mobileSelectedFile.hidden = true;
    mobileSelectedFileName.textContent = '';
    mobileSelectedFileMeta.textContent = '';
    mobileChooseFile.hidden = false;
    setFileError(null);
}

function clearRecordedPreview() {
    recordedPreviewUrl = null;
    try { cameraPreview.pause(); } catch (_) {}
    cameraPreview.removeAttribute('src');
    cameraPreview.srcObject = null;
    cameraPreview.muted = true;
    cameraPreview.autoplay = true;
    try { cameraPreview.load(); } catch (_) {}
    playRecordingButton.hidden = true;
    updatePlayButtonState();
}

function toggleRecordedPlayback() {
    if (!recordedPreviewUrl || cameraPreview.srcObject) return;
    if (cameraPreview.paused || cameraPreview.ended) {
        if (cameraPreview.ended) cameraPreview.currentTime = 0;
        cameraPreview.play().catch((error) => setCameraError(error.message || 'Не удалось воспроизвести запись'));
    } else {
        cameraPreview.pause();
    }
    updatePlayButtonState();
}

function updatePlayButtonState() {
    if (!playRecordingButton) return;
    const playing = !!recordedPreviewUrl && !cameraPreview.paused && !cameraPreview.ended;
    playGlyph.textContent = playing ? 'Ⅱ' : '▶';
    playRecordingButton.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести запись');
    playRecordingButton.title = playing ? 'Пауза' : 'Воспроизвести запись';
}

function updateRecordButtonState(recording) {
    recordToggleButton.classList.toggle('is-recording', !!recording);
    recordToggleButton.setAttribute('aria-label', recording ? 'Остановить запись' : 'Начать запись');
    recordToggleButton.title = recording ? 'Остановить запись' : 'Начать запись';
}

function isRecordingActive() {
    return !!mediaRecorder && mediaRecorder.state !== 'inactive';
}

function stopCameraStream() {
    if (cameraStream) {
        cameraStream.getTracks().forEach((track) => track.stop());
        cameraStream = null;
    }
    cameraPreview.srcObject = null;
}

function supportsEmbeddedRecording() {
    return !!(navigator.mediaDevices
        && typeof navigator.mediaDevices.getUserMedia === 'function'
        && typeof window.MediaRecorder !== 'undefined');
}

function recorderTypeSupported(type) {
    return typeof window.MediaRecorder !== 'undefined'
        && typeof MediaRecorder.isTypeSupported === 'function'
        && MediaRecorder.isTypeSupported(type);
}

function chooseRecorderMimeType() {
    if (typeof window.MediaRecorder === 'undefined'
        || typeof MediaRecorder.isTypeSupported !== 'function') {
        return '';
    }
    const candidates = [
        'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
        'video/mp4',
        'video/webm;codecs=vp8,opus',
        'video/webm'
    ];
    return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || '';
}

async function queryMediaPermissionStates() {
    const result = {camera: 'unknown', microphone: 'unknown'};
    if (!navigator.permissions || typeof navigator.permissions.query !== 'function') {
        return result;
    }

    await Promise.all(['camera', 'microphone'].map(async (name) => {
        try {
            const status = await navigator.permissions.query({name});
            result[name] = status && status.state ? status.state : 'unknown';
        } catch (_) {
            // Android WebView versions differ in Permissions API support.
        }
    }));
    return result;
}

async function isPermissionRelatedCameraError(error) {
    const name = error && error.name ? error.name : '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') {
        return true;
    }
    if (!isBitrixMobile || (name !== 'NotReadableError' && name !== 'TrackStartError')) {
        return false;
    }

    // Bitrix Android WebView can report NotReadableError when the host app itself
    // has no CAMERA/RECORD_AUDIO runtime permission. This was observed on the target device.
    const states = await queryMediaPermissionStates();
    return states.camera !== 'granted' || states.microphone !== 'granted';
}

function cameraPermissionMessage(error) {
    const name = error && error.name ? error.name : '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') {
        return 'Bitrix24 не разрешён доступ к камере или микрофону. Разрешите оба доступа в настройках Android.';
    }
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
        return 'Камера или микрофон не найдены на устройстве.';
    }
    if (name === 'NotReadableError' || name === 'TrackStartError') {
        if (isBitrixMobile) {
            return 'Bitrix24 не смог открыть камеру или микрофон. Проверьте разрешения Bitrix24 в Android; если они уже разрешены, закройте другое приложение, которое может использовать камеру.';
        }
        return 'Камера или микрофон сейчас недоступны. Закройте другое приложение, которое может их использовать, и повторите.';
    }
    return 'Не удалось открыть камеру: ' + (error && error.message ? error.message : name || 'неизвестная ошибка');
}

function showPermissionDialog(message) {
    permissionMessage.textContent = message || 'Разрешите Bitrix24 использовать камеру и микрофон для записи видео.';
    permissionFallback.hidden = true;
    openPermissionsButton.hidden = !isBitrixMobile || !/Android/i.test(navigator.userAgent || '');
    permissionDialog.hidden = false;
    fitWindow();
}

function hidePermissionDialog() {
    permissionDialog.hidden = true;
    permissionFallback.hidden = true;
}

function openBitrixAndroidSettings() {
    permissionReturnPending = true;
    try {
        sessionStorage.setItem('b24-awaiting-media-permission', '1');
    } catch (_) {}
    reportClientEvent('PERMISSION_SETTINGS_REQUESTED', 'android.application.details');

    // A web page inside Bitrix24 cannot call Android Settings APIs directly.
    // This intent is a best-effort bridge for WebViews that allow external intents.
    const intentUrl = 'intent:com.bitrix24.android#Intent;scheme=package;action=android.settings.APPLICATION_DETAILS_SETTINGS;end';
    try {
        const anchor = document.createElement('a');
        anchor.href = intentUrl;
        anchor.target = '_blank';
        anchor.rel = 'noopener';
        anchor.style.display = 'none';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
    } catch (_) {
        // The fallback instructions below remain available.
    }

    window.setTimeout(() => {
        if (!document.hidden) {
            permissionFallback.hidden = false;
            fitWindow();
        }
    }, 900);
}

async function retryPermissionsNow() {
    permissionReturnPending = true;
    hidePermissionDialog();
    await retryCameraAfterPermissionReturn(true);
}

async function retryCameraAfterPermissionReturn(force = false) {
    if (permissionRetryInFlight || !selectedEntity || mobileSourceModeInput.value !== 'CAMERA' || !supportsEmbeddedRecording()) return;
    let awaiting = permissionReturnPending;
    try {
        awaiting = awaiting || sessionStorage.getItem('b24-awaiting-media-permission') === '1';
    } catch (_) {}
    if (!force && !awaiting) return;

    permissionRetryInFlight = true;
    try {
        // Small delay lets Android/WebView refresh runtime permission state after returning.
        await sleep(250);
        const started = await startCamera(false);
        if (started) {
            permissionReturnPending = false;
            try { sessionStorage.removeItem('b24-awaiting-media-permission'); } catch (_) {}
            hidePermissionDialog();
            reportClientEvent('PERMISSION_RETRY_SUCCEEDED', 'camera+microphone');
        } else {
            permissionReturnPending = true;
        }
    } finally {
        permissionRetryInFlight = false;
    }
}

function renderTimer() {
    if (recordingStartedAt == null) return;
    const elapsedMs = Math.max(0, performance.now() - recordingStartedAt);
    const seconds = Math.floor(elapsedMs / 1000);
    const minutes = String(Math.floor(seconds / 60)).padStart(2, '0');
    const rest = String(seconds % 60).padStart(2, '0');
    recordingTimer.textContent = `${minutes}:${rest}`;
}

function startTimer() {
    stopTimer(true);
    recordingStartedAt = performance.now();
    renderTimer();
    timerHandle = window.setInterval(renderTimer, 250);
}

function stopTimer(resetDisplay = false) {
    if (recordingStartedAt != null && !resetDisplay) {
        renderTimer();
    }
    if (timerHandle != null) {
        clearInterval(timerHandle);
    }
    timerHandle = null;
    recordingStartedAt = null;
    if (resetDisplay) {
        recordingTimer.textContent = '00:00';
    }
}

async function acquireWakeLock() {
    if (!navigator.wakeLock || typeof navigator.wakeLock.request !== 'function') return;
    try {
        wakeLock = await navigator.wakeLock.request('screen');
    } catch (_) {
        wakeLock = null;
    }
}

async function releaseWakeLock() {
    if (!wakeLock) return;
    try { await wakeLock.release(); } catch (_) {}
    wakeLock = null;
}

function clearCameraMessages() {
    setCameraError(null);
}

function setCameraError(message) {
    cameraError.hidden = !message;
    cameraError.textContent = message || '';
}

async function checkOfferStatus() {
    if (!activeOfferId) return;
    try {
        const response = await fetch('/api/video-offers/' + activeOfferId, {cache: 'no-store'});
        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось проверить статус');
        }
        renderOffer(data);
        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING'
            && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'ERROR' || data.status === 'CANCELLED'
            || (data.status === 'READY' && deliveryFinished)) {
            clearInterval(offerPollTimer);
            submitButton.disabled = false;
            submitButton.textContent = 'Создать ещё один оффер';
        }
    } catch (error) {
        finishOfferWithError(error.message);
    }
}

function renderOffer(data) {
    const percent = Math.max(0, Math.min(100, data.progressPercent || 100));
    const statusText = {
        QUEUED: 'Задача поставлена в очередь',
        PREPARING: 'Подготавливаем видео',
        READY: 'Публичная страница готова',
        ERROR: data.errorMessage || 'Ошибка подготовки видео',
        CANCELLED: 'Подготовка отменена'
    };
    renderProgress(percent, statusText[data.status] || data.status);

    if (data.status === 'READY') {
        publicLink.href = data.publicUrl + '?preview=1';
        readyResult.hidden = false;
        if (data.bitrixDeliveryStatus === 'DELIVERED') {
            deliveryStatus.textContent = 'Ссылка добавлена в таймлайн выбранной карточки.';
            readyMessage.textContent = 'Ссылка добавлена в Bitrix24. ' + viewGoalMessage(data.viewNotificationGoal);
        } else if (data.bitrixDeliveryStatus === 'ERROR') {
            deliveryStatus.textContent = 'Видео готово, но Bitrix24 пока не принял комментарий. Приложение повторит отправку автоматически.';
            readyMessage.textContent = data.bitrixDeliveryError
                ? 'Ошибка добавления ссылки: ' + data.bitrixDeliveryError
                : 'Страница готова, но ссылку пока не удалось добавить в карточку.';
        } else {
            deliveryStatus.textContent = 'Добавляем ссылку в таймлайн карточки…';
            readyMessage.textContent = 'Публичная страница готова. Завершаем запись ссылки в Bitrix24.';
        }
    }

    if (data.status === 'ERROR') {
        setFormError(data.errorMessage || 'Не удалось подготовить видеооффер');
    }
    fitWindow();
}

function renderProgress(percent, text) {
    processing.hidden = false;
    progressBar.style.width = percent + '%';
    processingPercent.textContent = percent + '%';
    processingStatus.textContent = text;
}

function finishOfferWithError(message) {
    clearInterval(offerPollTimer);
    setFormError(message);
    submitButton.disabled = false;
    submitButton.textContent = 'Создать видеооффер';
    fitWindow();
}

function resetOfferOutput() {
    activeOfferId = null;
    clearInterval(offerPollTimer);
    processing.hidden = true;
    readyResult.hidden = true;
    setFormError(null);
    submitButton.disabled = false;
    submitButton.textContent = 'Создать видеооффер';
}

function setFormError(message) {
    formError.hidden = !message;
    formError.textContent = message || '';
}

async function readJson(response) {
    const text = await response.text();
    if (!text) return {};
    try {
        return JSON.parse(text);
    } catch (_) {
        return {message: text};
    }
}

function parseJsonText(text) {
    if (!text) return {};
    try { return JSON.parse(text); } catch (_) { return {message: text}; }
}

function typeShort(type) {
    return {LEAD: 'Л', CONTACT: 'К', DEAL: 'С'}[type] || '?';
}

function typeLabel(type) {
    return {LEAD: 'Лид', CONTACT: 'Контакт', DEAL: 'Сделка'}[type] || type;
}

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(() => fitWindow());
}

function fitWindow() {
    // Standalone BitrixMobile pages are already full-screen WebViews. Calling fitWindow there
    // creates unnecessary native bridge traffic and is intentionally avoided.
    if (isBitrixMobile || typeof BX24 === 'undefined') return;
    try {
        BX24.fitWindow();
    } catch (_) {
        // The standalone page must also work outside an embedded desktop frame.
    }
}

function viewGoalMessage(goal) {
    const messages = {
        ONE_MINUTE: 'Уведомим, когда клиент посмотрит одну минуту видео.',
        HALF: 'Уведомим, когда клиент досмотрит видео до середины.',
        COMPLETED: 'Уведомим, когда клиент досмотрит видео целиком.',
        NONE: 'Уведомление о просмотре отключено.'
    };
    return messages[goal] || '';
}

function formatBytes(bytes) {
    const value = Number(bytes || 0);
    if (value < 1024 * 1024) return Math.max(0, Math.round(value / 1024)) + ' КБ';
    return (value / 1024 / 1024).toFixed(value >= 100 * 1024 * 1024 ? 0 : 1) + ' МБ';
}

function reportClientEvent(event, details) {
    if (!mobileContextToken) return;
    fetch('/bitrix/mobile/client-events', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            mobileContextToken,
            event: String(event || ''),
            details: String(details || '')
        }),
        keepalive: true
    }).catch(() => {});
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
