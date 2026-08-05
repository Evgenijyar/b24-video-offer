const mobileContextToken = document.getElementById('mobile-context-token').value;
const entityTypeSelect = document.getElementById('entity-type');
const searchInput = document.getElementById('search-query');
const clearSearchButton = document.getElementById('clear-search');
const searchState = document.getElementById('search-state');
const searchResults = document.getElementById('search-results');
const selectedCard = document.getElementById('selected-card');
const selectedTitle = document.getElementById('selected-title');
const selectedMeta = document.getElementById('selected-meta');
const changeSelectionButton = document.getElementById('change-selection');
const offerSection = document.getElementById('offer-section');
const selectedContextToken = document.getElementById('selected-context-token');
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

let debounceTimer = null;
let activeSearchController = null;
let activeOfferId = null;
let pollTimer = null;
let searchSequence = 0;

initializeBitrixFrame();
searchInput.focus();

searchInput.addEventListener('input', () => {
    clearSearchButton.hidden = !searchInput.value;
    scheduleSearch();
});

entityTypeSelect.addEventListener('change', () => {
    resetSelection();
    scheduleSearch(true);
});

clearSearchButton.addEventListener('click', () => {
    searchInput.value = '';
    clearSearchButton.hidden = true;
    resetSearchOutput('Начните вводить название, имя, ID или телефон.');
    resetSelection();
    searchInput.focus();
});

changeSelectionButton.addEventListener('click', () => {
    resetSelection();
    searchInput.focus();
    searchInput.scrollIntoView({behavior: 'smooth', block: 'center'});
});

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
        contextToken: selectedContextToken.value,
        recordingUrl: document.getElementById('recording-url').value.trim(),
        accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
        viewNotificationGoal: document.getElementById('view-notification-goal').value
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

function scheduleSearch(immediate = false) {
    clearTimeout(debounceTimer);
    const query = searchInput.value.trim();
    resetSelection();

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
        entityType: entityTypeSelect.value,
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

function selectEntity(item) {
    selectedContextToken.value = item.contextToken;
    selectedTitle.textContent = item.title;
    selectedMeta.textContent = typeLabel(item.entityType) + ' №' + item.id
        + (item.subtitle ? ' · ' + item.subtitle : '');
    selectedCard.hidden = false;
    offerSection.hidden = false;
    searchResults.hidden = true;
    searchState.hidden = true;
    offerSection.scrollIntoView({behavior: 'smooth', block: 'start'});
    fitWindow();
}

function resetSelection() {
    selectedContextToken.value = '';
    selectedCard.hidden = true;
    offerSection.hidden = true;
    processing.hidden = true;
    readyResult.hidden = true;
    setError(null);
    activeOfferId = null;
    clearInterval(pollTimer);
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
        publicLink.href = data.publicUrl + '?preview=1';
        readyResult.hidden = false;
        if (data.bitrixDeliveryStatus === 'DELIVERED') {
            deliveryStatus.textContent = 'Ссылка добавлена в таймлайн выбранной карточки.';
            readyMessage.textContent = 'Ссылка добавлена в Bitrix24. ' + viewGoalMessage(data.viewNotificationGoal);
        } else if (data.bitrixDeliveryStatus === 'ERROR') {
            deliveryStatus.textContent = 'Видео готово, но Bitrix24 не принял комментарий.';
            readyMessage.textContent = data.bitrixDeliveryError
                ? 'Ошибка добавления ссылки в карточку: ' + data.bitrixDeliveryError
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

function typeShort(type) {
    return {LEAD: 'Л', CONTACT: 'К', DEAL: 'С'}[type] || '?';
}

function typeLabel(type) {
    return {LEAD: 'Лид', CONTACT: 'Контакт', DEAL: 'Сделка'}[type] || type;
}

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(function () {
        fitWindow();
    });
}

function fitWindow() {
    if (typeof BX24 === 'undefined') return;
    try {
        BX24.fitWindow();
    } catch (_) {
        // Интерфейс также должен корректно работать при диагностическом открытии.
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
