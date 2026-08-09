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
const cameraInfo = document.getElementById('camera-info');
const startCameraButton = document.getElementById('start-camera');
const switchCameraButton = document.getElementById('switch-camera');
const startRecordingButton = document.getElementById('start-recording');
const stopRecordingButton = document.getElementById('stop-recording');
const recordingBadge = document.getElementById('recording-badge');
const recordingTimer = document.getElementById('recording-timer');
const fallbackFileButton = document.getElementById('fallback-file-button');
const fallbackFileInput = document.getElementById('fallback-file');
const uploadProcessing = document.getElementById('upload-processing');
const uploadStatus = document.getElementById('upload-status');
const uploadBytes = document.getElementById('upload-bytes');

const recordedSection = document.getElementById('recorded-section');
const recordedPreview = document.getElementById('recorded-preview');
const retryRecordingButton = document.getElementById('retry-recording');
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

const isBitrixMobile = /BitrixMobile/i.test(navigator.userAgent || '');
const MAX_FALLBACK_FILE_BYTES = 512 * 1024 * 1024;
const FALLBACK_CHUNK_BYTES = 4 * 1024 * 1024;
const MEDIA_CHUNK_INTERVAL_MS = 2000;

let debounceTimer = null;
let activeSearchController = null;
let searchSequence = 0;
let selectedEntity = null;

let cameraStream = null;
let mediaRecorder = null;
let facingMode = 'user';
let recordingStartedAt = null;
let timerHandle = null;
let wakeLock = null;
let uploadSession = null;
let uploadChain = Promise.resolve();
let uploadFailure = null;
let recordingFinalizing = false;
let normalizationPollTimer = null;
let activeOfferId = null;
let offerPollTimer = null;

initializeBitrixFrame();
initializeEntityPicker();
initializeGoalPicker();
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

retryRecordingButton.addEventListener('click', async () => {
    await resetRecordingState(true);
    recorderSection.hidden = false;
    setCameraInfo('Можно записать новый ролик. Предыдущая незавершённая загрузка автоматически удалится сервером позднее.');
    recorderSection.scrollIntoView({behavior: 'smooth', block: 'start'});
});

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!uploadSession || uploadSession.status !== 'READY') {
        setFormError('Сначала дождитесь сохранения видео на сервере.');
        return;
    }

    clearInterval(offerPollTimer);
    setFormError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(100, 'Создаём видеооффер…');
    deliveryStatus.textContent = 'Добавляем ссылку в выбранную карточку Bitrix24…';

    try {
        const response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/offer`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                uploadToken: uploadSession.uploadToken,
                accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                viewNotificationGoal: goalInput.value
            })
        });
        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось создать видеооффер');
        }

        activeOfferId = data.id;
        renderOffer(data);
        if (data.bitrixDeliveryStatus === 'PENDING' || data.bitrixDeliveryStatus === 'SENDING') {
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

function initializeRecorder() {
    startCameraButton.addEventListener('click', () => startCamera(false));
    switchCameraButton.addEventListener('click', switchCamera);
    startRecordingButton.addEventListener('click', startRecording);
    stopRecordingButton.addEventListener('click', stopRecording);
    fallbackFileButton.addEventListener('click', () => fallbackFileInput.click());
    fallbackFileInput.addEventListener('change', () => {
        const file = fallbackFileInput.files && fallbackFileInput.files[0];
        if (file) uploadFallbackFile(file);
        fallbackFileInput.value = '';
    });

    if (!supportsEmbeddedRecording()) {
        startCameraButton.hidden = true;
        switchCameraButton.hidden = true;
        startRecordingButton.hidden = true;
        setCameraInfo('Встроенная камера недоступна в этом WebView. Используйте системную камеру/галерею ниже.');
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
    recordedSection.hidden = true;
    offerSection.hidden = true;
    searchResults.hidden = true;
    searchState.hidden = true;
    clearCameraMessages();
    recorderSection.scrollIntoView({behavior: 'smooth', block: 'start'});
    fitWindow();
}

async function resetSelection() {
    selectedEntity = null;
    selectedCard.hidden = true;
    recorderSection.hidden = true;
    await resetRecordingState(false);
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
    if (!selectedEntity) return;
    if (!supportsEmbeddedRecording()) {
        setCameraError('Этот WebView не предоставляет MediaRecorder/getUserMedia. Используйте системную камеру.');
        return;
    }

    clearCameraMessages();
    startCameraButton.disabled = true;
    startCameraButton.textContent = switching ? 'Переключаем…' : 'Включаем…';
    try {
        stopCameraStream();
        const constraints = {
            video: {
                facingMode: {ideal: facingMode},
                width: {ideal: 1280, max: 1920},
                height: {ideal: 720, max: 1080},
                frameRate: {ideal: 30, max: 30}
            },
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                channelCount: 1
            }
        };
        cameraStream = await navigator.mediaDevices.getUserMedia(constraints);
        cameraPreview.srcObject = cameraStream;
        await cameraPreview.play().catch(() => {});
        cameraPlaceholder.hidden = true;
        cameraPreview.hidden = false;
        startCameraButton.hidden = true;
        switchCameraButton.hidden = false;
        startRecordingButton.hidden = false;
        setCameraInfo(facingMode === 'user' ? 'Фронтальная камера готова.' : 'Основная камера готова.');
        reportClientEvent('CAMERA_READY', facingMode);
    } catch (error) {
        stopCameraStream();
        startCameraButton.hidden = false;
        switchCameraButton.hidden = true;
        startRecordingButton.hidden = true;
        const message = cameraPermissionMessage(error);
        setCameraError(message);
        reportClientEvent('CAMERA_ERROR', (error && error.name ? error.name : 'Error') + ': ' + (error && error.message ? error.message : message));
    } finally {
        startCameraButton.disabled = false;
        startCameraButton.textContent = 'Включить камеру';
    }
}

async function switchCamera() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') return;
    facingMode = facingMode === 'user' ? 'environment' : 'user';
    await startCamera(true);
}

async function startRecording() {
    if (!selectedEntity || !cameraStream) return;
    if (mediaRecorder && mediaRecorder.state !== 'inactive') return;

    clearCameraMessages();
    resetOfferOutput();
    uploadSession = null;
    uploadFailure = null;
    uploadChain = Promise.resolve();
    recordingFinalizing = false;

    try {
        const mimeType = chooseRecorderMimeType();
        const recorderOptions = {
            videoBitsPerSecond: 2_500_000,
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

        mediaRecorder.start(MEDIA_CHUNK_INTERVAL_MS);
        recordingStartedAt = Date.now();
        startTimer();
        await acquireWakeLock();
        reportClientEvent('RECORDING_STARTED', mediaRecorder.mimeType || mimeType || 'default');

        startRecordingButton.hidden = true;
        switchCameraButton.hidden = true;
        stopRecordingButton.hidden = false;
        recordingBadge.hidden = false;
        uploadProcessing.hidden = false;
        uploadStatus.textContent = 'Идёт запись и защищённая загрузка на сервер…';
        uploadBytes.textContent = '0 МБ';
        setCameraInfo('Видео загружается на сервер небольшими частями прямо во время записи.');
    } catch (error) {
        setCameraError(error.message || 'Не удалось начать запись');
        mediaRecorder = null;
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
    const blob = event.data;
    uploadChain = uploadChain.then(async () => {
        if (uploadFailure) return;
        try {
            const updated = await uploadChunkWithRetry(blob);
            uploadSession = updated;
            uploadBytes.textContent = formatBytes(updated.bytesReceived);
        } catch (error) {
            uploadFailure = error;
            setCameraError('Потеряна загрузка видео: ' + (error.message || 'ошибка сети'));
            if (mediaRecorder && mediaRecorder.state !== 'inactive') {
                try { mediaRecorder.stop(); } catch (_) {}
            }
        }
    });
}

function stopRecording() {
    if (!mediaRecorder || mediaRecorder.state === 'inactive' || recordingFinalizing) return;
    recordingFinalizing = true;
    stopRecordingButton.disabled = true;
    stopRecordingButton.textContent = 'Завершаем…';
    uploadStatus.textContent = 'Завершаем запись и отправляем последнюю часть…';
    try {
        mediaRecorder.stop();
    } catch (error) {
        recordingFinalizing = false;
        stopRecordingButton.disabled = false;
        stopRecordingButton.textContent = 'Остановить запись';
        setCameraError(error.message || 'Не удалось остановить запись');
    }
}

async function finalizeRecordedVideo() {
    stopTimer();
    await releaseWakeLock();
    recordingBadge.hidden = true;
    stopRecordingButton.hidden = true;
    stopRecordingButton.disabled = false;
    stopRecordingButton.textContent = 'Остановить запись';
    stopCameraStream();

    try {
        await uploadChain;
        if (uploadFailure) throw uploadFailure;
        uploadStatus.textContent = 'Запись загружена. Сжимаем и переводим в MP4…';
        uploadSession = await completeUploadSession();
        await waitForNormalization();
    } catch (error) {
        setCameraError(error.message || 'Не удалось сохранить видео');
        uploadProcessing.hidden = true;
        startCameraButton.hidden = false;
    } finally {
        mediaRecorder = null;
        recordingFinalizing = false;
    }
}

async function uploadFallbackFile(file) {
    if (!selectedEntity) return;
    if (!file || file.size <= 0) return;
    if (file.size > MAX_FALLBACK_FILE_BYTES) {
        setCameraError('Файл слишком большой. Максимальный размер — 512 МБ.');
        return;
    }

    await resetRecordingState(false);
    recorderSection.hidden = false;
    clearCameraMessages();
    uploadProcessing.hidden = false;
    uploadStatus.textContent = 'Загружаем выбранное видео…';
    uploadBytes.textContent = '0%';
    startCameraButton.hidden = true;
    switchCameraButton.hidden = true;
    startRecordingButton.hidden = true;

    try {
        uploadSession = await createUploadSession(file.type || 'application/octet-stream');
        let offset = 0;
        while (offset < file.size) {
            const blob = file.slice(offset, Math.min(file.size, offset + FALLBACK_CHUNK_BYTES));
            uploadSession = await uploadChunkWithRetry(blob);
            offset += blob.size;
            const percent = Math.min(100, Math.round((offset / file.size) * 100));
            uploadBytes.textContent = percent + '% · ' + formatBytes(offset);
        }
        uploadStatus.textContent = 'Видео загружено. Сжимаем и переводим в MP4…';
        uploadSession = await completeUploadSession();
        await waitForNormalization();
    } catch (error) {
        setCameraError(error.message || 'Не удалось загрузить видео');
        uploadProcessing.hidden = true;
        startCameraButton.hidden = !supportsEmbeddedRecording();
    }
}

async function createUploadSession(mimeType) {
    if (!selectedEntity || !selectedEntity.contextToken) {
        throw new Error('Не выбрана карточка Bitrix24');
    }
    const response = await fetch('/bitrix/mobile/uploads', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            contextToken: selectedEntity.contextToken,
            mimeType: mimeType || 'application/octet-stream'
        })
    });
    const data = await readJson(response);
    if (!response.ok) {
        throw new Error(data.message || 'Не удалось создать сессию загрузки');
    }
    return data;
}

async function uploadChunkWithRetry(blob) {
    let lastError = null;
    for (let attempt = 1; attempt <= 4; attempt++) {
        try {
            const response = await fetch(
                `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/chunks/${uploadSession.nextSequence}`,
                {
                    method: 'PUT',
                    headers: {
                        'Content-Type': 'application/octet-stream',
                        'X-Upload-Token': uploadSession.uploadToken
                    },
                    body: blob
                });
            const data = await readJson(response);
            if (!response.ok) {
                throw new Error(data.message || 'Сервер не принял часть видео');
            }
            return data;
        } catch (error) {
            lastError = error;
            if (attempt < 4) {
                await sleep(attempt * 700);
            }
        }
    }
    throw lastError || new Error('Не удалось загрузить часть видео');
}

async function completeUploadSession() {
    const response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/complete`, {
        method: 'POST',
        headers: {'X-Upload-Token': uploadSession.uploadToken}
    });
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
        const response = await fetch(
            `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}?uploadToken=${encodeURIComponent(uploadSession.uploadToken)}`,
            {cache: 'no-store'});
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
        uploadStatus.textContent = data.status === 'PROCESSING'
            ? 'Сжимаем и переводим видео в MP4…'
            : 'Подготавливаем видео…';
        await sleep(1000);
    }
    throw new Error('Обработка видео заняла слишком много времени');
}

function showNormalizedVideo(data) {
    uploadProcessing.hidden = true;
    cameraPreview.hidden = true;
    cameraPlaceholder.hidden = false;
    startCameraButton.hidden = true;
    switchCameraButton.hidden = true;
    startRecordingButton.hidden = true;
    recordedPreview.src = data.previewUrl || '';
    recordedSection.hidden = false;
    offerSection.hidden = false;
    setCameraInfo('Видео сохранено на сервере в MP4. Теперь добавьте текст и создайте оффер.');
    reportClientEvent('UPLOAD_READY', 'bytes=' + Number(data.bytesReceived || 0));
    recordedSection.scrollIntoView({behavior: 'smooth', block: 'start'});
    fitWindow();
}

async function resetRecordingState(showCameraButton) {
    stopTimer();
    clearInterval(normalizationPollTimer);
    clearInterval(offerPollTimer);
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
    uploadFailure = null;
    uploadChain = Promise.resolve();
    recordedPreview.pause();
    recordedPreview.removeAttribute('src');
    recordedPreview.load();
    recordedSection.hidden = true;
    offerSection.hidden = true;
    processing.hidden = true;
    readyResult.hidden = true;
    uploadProcessing.hidden = true;
    recordingBadge.hidden = true;
    recordingTimer.textContent = '00:00';
    stopRecordingButton.hidden = true;
    stopRecordingButton.disabled = false;
    stopRecordingButton.textContent = 'Остановить запись';
    switchCameraButton.hidden = true;
    startRecordingButton.hidden = true;
    startCameraButton.hidden = !showCameraButton || !supportsEmbeddedRecording();
    cameraPreview.hidden = false;
    cameraPlaceholder.hidden = false;
    clearCameraMessages();
    resetOfferOutput();
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

function cameraPermissionMessage(error) {
    const name = error && error.name ? error.name : '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
        return 'Bitrix24 не дал доступ к камере или микрофону. Разрешите камеру и микрофон для приложения Bitrix24 в настройках телефона и откройте видеооффер снова.';
    }
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
        return 'Камера или микрофон не найдены на устройстве.';
    }
    if (name === 'NotReadableError' || name === 'TrackStartError') {
        return 'Камера сейчас занята другим приложением. Закройте его и повторите.';
    }
    return 'Не удалось открыть камеру: ' + (error && error.message ? error.message : name || 'неизвестная ошибка');
}

function startTimer() {
    stopTimer();
    const render = () => {
        if (!recordingStartedAt) return;
        const seconds = Math.max(0, Math.floor((Date.now() - recordingStartedAt) / 1000));
        const minutes = String(Math.floor(seconds / 60)).padStart(2, '0');
        const rest = String(seconds % 60).padStart(2, '0');
        recordingTimer.textContent = `${minutes}:${rest}`;
    };
    render();
    timerHandle = setInterval(render, 500);
}

function stopTimer() {
    clearInterval(timerHandle);
    timerHandle = null;
    recordingStartedAt = null;
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
    setCameraInfo(null);
}

function setCameraError(message) {
    cameraError.hidden = !message;
    cameraError.textContent = message || '';
}

function setCameraInfo(message) {
    cameraInfo.hidden = !message;
    cameraInfo.textContent = message || '';
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
