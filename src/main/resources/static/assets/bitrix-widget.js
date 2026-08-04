const form = document.getElementById('bitrix-offer-form');
const contextToken = document.getElementById('context-token').value;
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

let activeOfferId = null;
let pollTimer = null;

initializeBitrixFrame();

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearInterval(pollTimer);
    setError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(0, 'Создаём задачу…');
    fitWindow();

    const payload = {
        contextToken,
        recordingUrl: document.getElementById('recording-url').value.trim(),
        accompanyingText: document.getElementById('accompanying-text').value.trim() || null
    };

    try {
        const response = await fetch('/bitrix/video-offers', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось создать видеооффер');
        }

        activeOfferId = data.id;
        renderOffer(data);
        pollTimer = setInterval(checkStatus, 1000);
        await checkStatus();
    } catch (error) {
        finishWithError(error.message);
    }
});

async function checkStatus() {
    if (!activeOfferId) return;

    try {
        const response = await fetch('/api/video-offers/' + activeOfferId, {cache: 'no-store'});
        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось проверить статус');
        }

        renderOffer(data);

        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING';
        if (data.status === 'ERROR' || data.status === 'CANCELLED'
            || (data.status === 'READY' && deliveryFinished)) {
            clearInterval(pollTimer);
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        }
    } catch (error) {
        finishWithError(error.message);
    }
}

function renderOffer(data) {
    const percent = Math.max(0, Math.min(100, data.progressPercent || 0));
    const statusText = {
        QUEUED: 'Задача поставлена в очередь',
        PREPARING: 'Скачиваем запись Контур.Толка',
        READY: 'Публичная страница готова',
        ERROR: data.errorMessage || 'Ошибка подготовки видео',
        CANCELLED: 'Подготовка отменена'
    };

    renderProgress(percent, statusText[data.status] || data.status);

    if (data.status === 'READY') {
        publicLink.href = data.publicUrl;
        readyResult.hidden = false;

        if (data.bitrixDeliveryStatus === 'DELIVERED') {
            deliveryStatus.textContent = 'Ссылка добавлена в таймлайн текущей карточки.';
            readyMessage.textContent = 'Ссылка автоматически добавлена в таймлайн этой карточки Bitrix24.';
        } else if (data.bitrixDeliveryStatus === 'ERROR') {
            deliveryStatus.textContent = 'Видео готово, но Bitrix24 не принял комментарий.';
            readyMessage.textContent = data.bitrixDeliveryError
                ? 'Страница готова. Ошибка добавления в карточку: ' + data.bitrixDeliveryError
                : 'Страница готова, но ссылку не удалось добавить в карточку.';
        } else {
            deliveryStatus.textContent = 'Добавляем ссылку в таймлайн карточки…';
            readyMessage.textContent = 'Публичная страница готова. Завершаем запись ссылки в Bitrix24.';
        }
    }

    if (data.status === 'ERROR') {
        setError(data.errorMessage || 'Не удалось подготовить видеооффер');
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

function setError(message) {
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

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(function () {
        BX24.resizeWindow(780, 720);
        BX24.fitWindow();
    });
}

function fitWindow() {
    if (typeof BX24 === 'undefined') return;
    try {
        BX24.fitWindow();
    } catch (_) {
        // Страница также должна работать при прямом диагностическом открытии.
    }
}
