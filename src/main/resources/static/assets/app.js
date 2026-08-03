const form = document.getElementById('offer-form');
const submitButton = document.getElementById('submit-button');
const formError = document.getElementById('form-error');
const processing = document.getElementById('processing');
const progressBar = document.getElementById('progress-bar');
const processingPercent = document.getElementById('processing-percent');
const processingStatus = document.getElementById('processing-status');
const readyResult = document.getElementById('ready-result');
const localLink = document.getElementById('local-link');
const publicUrl = document.getElementById('public-url');
const copyLink = document.getElementById('copy-link');
const offersList = document.getElementById('offers-list');

let activeOfferId = null;
let activePublicUrl = null;
let pollTimer = null;

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    resetResult();
    setError(null);
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';

    const payload = {
        entityType: document.getElementById('entity-type').value,
        entityId: Number(document.getElementById('entity-id').value),
        recordingUrl: document.getElementById('recording-url').value.trim(),
        accompanyingText: document.getElementById('accompanying-text').value.trim() || null
    };

    try {
        const response = await fetch('/api/video-offers', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось создать видеооффер');
        }
        activeOfferId = data.id;
        activePublicUrl = data.publicUrl;
        processing.hidden = false;
        renderStatus(data);
        startPolling();
        await loadRecentOffers();
    } catch (error) {
        setError(error.message);
        submitButton.disabled = false;
        submitButton.textContent = 'Сформировать видеооффер';
    }
});

copyLink.addEventListener('click', async () => {
    if (!activePublicUrl) return;
    await navigator.clipboard.writeText(activePublicUrl);
    copyLink.textContent = 'Ссылка скопирована';
    setTimeout(() => copyLink.textContent = 'Скопировать публичную ссылку', 1600);
});

document.getElementById('refresh-list').addEventListener('click', loadRecentOffers);

function startPolling() {
    clearInterval(pollTimer);
    pollTimer = setInterval(checkStatus, 1000);
    checkStatus();
}

async function checkStatus() {
    if (!activeOfferId) return;
    try {
        const response = await fetch('/api/video-offers/' + activeOfferId, {cache: 'no-store'});
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось проверить статус');
        renderStatus(data);
        if (data.status === 'READY' || data.status === 'ERROR' || data.status === 'CANCELLED') {
            clearInterval(pollTimer);
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать видеооффер';
            await loadRecentOffers();
        }
    } catch (error) {
        clearInterval(pollTimer);
        setError(error.message);
        submitButton.disabled = false;
        submitButton.textContent = 'Сформировать видеооффер';
    }
}

function renderStatus(data) {
    const percent = Math.max(0, Math.min(100, data.progressPercent || 0));
    progressBar.style.width = percent + '%';
    processingPercent.textContent = percent + '%';

    const statusText = {
        QUEUED: 'Задача поставлена в очередь',
        PREPARING: 'Скачиваем запись Контур.Толка на сервер',
        READY: 'Видео загружено, публичная страница готова',
        ERROR: data.errorMessage || 'При подготовке произошла ошибка',
        CANCELLED: 'Подготовка отменена'
    };
    processingStatus.textContent = statusText[data.status] || data.status;

    if (data.status === 'READY') {
        activePublicUrl = data.publicUrl;
        readyResult.hidden = false;
        localLink.href = location.origin + data.relativePath;
        publicUrl.textContent = data.publicUrl;
    }
    if (data.status === 'ERROR') {
        setError(data.errorMessage || 'Не удалось подготовить видео');
    }
}

async function loadRecentOffers() {
    try {
        const response = await fetch('/api/video-offers', {cache: 'no-store'});
        const offers = await readJson(response);
        if (!response.ok) throw new Error(offers.message || 'Ошибка загрузки истории');
        if (!offers.length) {
            offersList.innerHTML = '<div class="empty-state">Видеоофферов пока нет</div>';
            return;
        }
        offersList.innerHTML = offers.map(renderOfferRow).join('');
    } catch (error) {
        offersList.innerHTML = '<div class="empty-state empty-state--error">' + escapeHtml(error.message) + '</div>';
    }
}

function renderOfferRow(offer) {
    const labels = {DEAL: 'Сделка', LEAD: 'Лид', CONTACT: 'Контакт'};
    const statusLabels = {QUEUED: 'В очереди', PREPARING: 'Загрузка', READY: 'Готов', ERROR: 'Ошибка', CANCELLED: 'Отменён'};
    const localHref = location.origin + offer.relativePath;
    return `
        <article class="offer-row">
            <div class="offer-row__main">
                <strong>${labels[offer.entityType] || offer.entityType} №${offer.entityId}</strong>
                <span>${formatDate(offer.createdAt)}</span>
            </div>
            <div class="offer-row__progress">
                <div class="mini-progress"><div style="width:${offer.progressPercent || 0}%"></div></div>
                <span class="status-pill status-pill--${offer.status.toLowerCase()}">${statusLabels[offer.status] || offer.status}</span>
            </div>
            <div class="offer-row__action">
                ${offer.status === 'READY' ? `<a href="${localHref}" target="_blank" rel="noopener">Открыть</a>` : ''}
                ${offer.status === 'ERROR' ? `<span title="${escapeHtml(offer.errorMessage || '')}">Подробнее</span>` : ''}
            </div>
        </article>`;
}

function resetResult() {
    readyResult.hidden = true;
    processing.hidden = true;
    progressBar.style.width = '0%';
    processingPercent.textContent = '0%';
    clearInterval(pollTimer);
}

function setError(message) {
    formError.hidden = !message;
    formError.textContent = message || '';
}

async function readJson(response) {
    const text = await response.text();
    if (!text) return {};
    try { return JSON.parse(text); } catch (_) { return {message: text}; }
}

function formatDate(value) {
    if (!value) return '';
    return new Intl.DateTimeFormat('ru-RU', {dateStyle: 'short', timeStyle: 'short'}).format(new Date(value));
}

function escapeHtml(value) {
    return String(value || '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

loadRecentOffers();
