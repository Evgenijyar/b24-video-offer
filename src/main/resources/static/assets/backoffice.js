const state = {
    tenants: [],
    selectedId: null,
    details: null,
    activeTab: 'settings',
    offers: [],
    offersLoadedFor: null,
    pageTemplate: null,
    pageTemplateLoadedFor: null,
    pageBuilder: null,
    offerSort: { key: 'createdAt', direction: 'desc', period: '7' },
    wizard: createWizardState()
};

const backofficeCsrf = document.querySelector('meta[name="backoffice-csrf"]')?.content || '';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[char]));
const fmtBytes = value => {
    const n = Number(value || 0);
    if (n < 1024 ** 2) return (n / 1024).toFixed(1) + ' КБ';
    if (n < 1024 ** 3) return (n / 1024 ** 2).toFixed(1) + ' МБ';
    return (n / 1024 ** 3).toFixed(2) + ' ГБ';
};
const pct = (a, b) => b ? Math.min(100, Math.round(Number(a || 0) * 100 / Number(b || 1))) : 0;
let pendingRequests = 0;

function createWizardState() {
    return {
        step: 1,
        tenantId: null,
        testOk: false,
        testRunning: false,
        testController: null,
        testSeq: 0,
        users: [],
        finished: false
    };
}

document.addEventListener('DOMContentLoaded', () => {
    bind();
    loadTenants();
});

function bind() {
    $('logout').onclick = async () => {
        await api('/api/backoffice/logout', { method: 'POST' });
        location.reload();
    };
    $('btn-add-client').onclick = openWizard;
    document.querySelectorAll('[data-close-modal]').forEach(element => element.onclick = cancelWizard);
    document.querySelectorAll('[data-close-employee]').forEach(element => element.onclick = closeEmployee);
    $('wizard-next').onclick = wizardNext;
    $('wizard-back').onclick = wizardBack;
    $('wizard-finish').onclick = wizardFinish;
    $('retry-test').onclick = runConnectionTest;
    $('wizard-sync-users').onclick = wizardSyncUsers;
    $('employee-save').onclick = saveEmployee;
    $('tenant-list').onclick = async event => {
        const button = event.target.closest('[data-tenant-id]');
        if (!button) return;
        state.selectedId = Number(button.dataset.tenantId);
        state.activeTab = 'settings';
        state.offers = [];
        state.offersLoadedFor = null;
        state.pageTemplate = null;
        state.pageTemplateLoadedFor = null;
        state.pageBuilder = null;
        await loadDetails();
        render();
    };
    $('workspace').onclick = workspaceClick;
    $('workspace').addEventListener('submit', workspaceSubmit);
    $('workspace').addEventListener('change', workspaceChange);
}

async function api(url, options = {}) {
    pendingRequests += 1;
    setStatus('Работаю…');
    const method = String(options.method || 'GET').toUpperCase();
    const headers = {'Content-Type': 'application/json', ...(options.headers || {})};
    if (method !== 'GET' && method !== 'HEAD' && backofficeCsrf) headers['X-Backoffice-CSRF'] = backofficeCsrf;
    try {
        const response = await fetch(url, {...options, headers});
        let data = null;
        try { data = await response.json(); } catch (_) {}
        if (response.status === 401 || response.status === 403) {
            location.reload();
            throw new Error(data?.message || 'Сессия завершена');
        }
        if (!response.ok) throw new Error(data?.message || data?.error || `HTTP ${response.status}`);
        return data;
    } finally {
        pendingRequests = Math.max(0, pendingRequests - 1);
        if (pendingRequests === 0) setStatus('Готово');
    }
}

function setStatus(text) { $('global-status').textContent = text; }

async function loadTenants() {
    try {
        state.tenants = await api('/api/backoffice/tenants');
        if (!state.selectedId && state.tenants.length) state.selectedId = state.tenants[0].id;
        if (state.selectedId) await loadDetails();
        render();
    } catch (error) {
        $('workspace').innerHTML = `<div class="form-error">${esc(error.message)}</div>`;
    }
}

async function refreshList() { state.tenants = await api('/api/backoffice/tenants'); }
async function loadDetails() { state.details = state.selectedId ? await api(`/api/backoffice/tenants/${state.selectedId}`) : null; }

function render() {
    renderNav();
    renderWorkspace();
}

function renderNav() {
    const element = $('tenant-list');
    if (!state.tenants.length) {
        element.innerHTML = '<div class="history-empty">Клиенты пока не добавлены.</div>';
        return;
    }
    element.innerHTML = state.tenants.map(tenant => `
        <button class="tenant-nav ${tenant.id === state.selectedId ? 'is-active' : ''}" data-tenant-id="${tenant.id}">
            <b>${esc(tenant.name)}</b>
            <small>${esc(tenant.portalDomain)}</small>
            <span class="nav-meta"><small>${esc(tenant.packageName)} · ${tenant.seatsUsed}/${tenant.seatLimit}</small><small>${tenant.offersUsed}/${tenant.offerLimit}</small></span>
        </button>`).join('');
}

function renderWorkspace() {
    const workspace = $('workspace');
    const details = state.details;
    if (!details) {
        workspace.innerHTML = `<div class="empty-state"><div class="eyebrow">VIDEO OFFER</div><h2>Добавьте первого клиента</h2><button class="btn btn-save" data-open-wizard>Добавить клиента</button></div>`;
        return;
    }
    workspace.innerHTML = `
        <div class="workspace-heading">
            <div><div class="eyebrow">Клиент · ${esc(details.packageName)}</div><h1 class="workspace-title">${esc(details.name)}</h1><div class="workspace-meta">${esc(details.portalDomain)} · member_id ${esc(details.memberId || '—')}</div></div>
            <div class="workspace-actions"><span class="status-pill ${String(details.status).toLowerCase()}">${esc(details.status)}</span><button class="btn btn-flat" data-action="test">Проверить подключение</button></div>
        </div>
        <nav class="workspace-tabs" aria-label="Разделы клиента">
            ${tabButton('settings', 'Настройки')}
            ${tabButton('employees', 'Сотрудники')}
            ${tabButton('offers', 'Офферы')}
            ${tabButton('page', 'Страница')}
        </nav>
        <section class="workspace-tab-panel">${renderActiveTab(details)}</section>`;
    if (state.activeTab === 'employees') initBackofficeEmployeeDragDrop();
    if (state.activeTab === 'page') initBackofficePageBuilder();
}

function tabButton(id, label) {
    return `<button type="button" class="workspace-tab ${state.activeTab === id ? 'is-active' : ''}" data-main-tab="${id}">${label}</button>`;
}

function renderActiveTab(details) {
    if (state.activeTab === 'employees') return renderEmployeesTab(details);
    if (state.activeTab === 'offers') return renderOffersTab();
    if (state.activeTab === 'page') return renderPageTab();
    return renderSettingsTab(details);
}

function renderSettingsTab(details) {
    const disk = pct(details.diskUsedBytes, details.diskQuotaBytes);
    const offers = pct(details.offersUsed, details.offerLimit);
    const seats = pct(details.seatsUsed, details.seatLimit);
    return `
        <div class="metrics-grid compact-metrics">
            ${metric('Сотрудники', `${details.seatsUsed} / ${details.seatLimit}`, `Свободно ${Math.max(0, details.seatLimit - details.seatsUsed)}`, seats)}
            ${metric('Видеоофферы', `${details.offersUsed} / ${details.offerLimit}`, `Осталось ${Math.max(0, details.offerLimit - details.offersUsed)}`, offers)}
            ${metric('Диск', `${fmtBytes(details.diskUsedBytes)} / ${fmtBytes(details.diskQuotaBytes)}`, `Свободно ${fmtBytes(Math.max(0, details.diskQuotaBytes - details.diskUsedBytes))}`, disk)}
            ${metric('Главный админ', adminName(details), details.allowAnyEntity ? 'Любые документы CRM' : 'Только свои документы', null)}
        </div>
        <div class="settings-overview-grid">
            <article class="settings-card company-settings-card">
                <div class="settings-card-head"><div><div class="eyebrow">Компания</div><h3>Пакет и доступ</h3></div></div>
                <form data-tenant-form>
                    <div class="two-col-form">
                        <label>Название<input name="name" class="custom-input" value="${esc(details.name)}"></label>
                        <label>Портал<input name="portalDomain" class="custom-input" value="${esc(details.portalDomain)}"></label>
                        <label>Пакет<input name="packageName" class="custom-input" value="${esc(details.packageName)}"></label>
                        <label>Статус<select name="status" class="custom-input"><option ${details.status === 'ACTIVE' ? 'selected' : ''}>ACTIVE</option><option ${details.status === 'DISABLED' ? 'selected' : ''}>DISABLED</option><option ${details.status === 'PENDING' ? 'selected' : ''}>PENDING</option></select></label>
                        <label>Лимит сотрудников<input name="seatLimit" type="number" min="1" class="custom-input" value="${details.seatLimit}"></label>
                        <label>Лимит офферов<input name="offerLimit" type="number" min="1" class="custom-input" value="${details.offerLimit}"></label>
                        <label>Диск, ГБ<input name="diskQuotaGb" type="number" step=".1" min=".1" class="custom-input" value="${(details.diskQuotaBytes / 1024 ** 3).toFixed(2)}"></label>
                        <label>Хранить офферы, дней<input name="retentionDays" type="number" min="1" max="3650" class="custom-input" value="${details.retentionDays || 7}"></label>
                    </div>
                    <label class="toggle-card"><input name="allowAnyEntity" type="checkbox" ${details.allowAnyEntity ? 'checked' : ''}><span><b>Разрешить любые документы CRM</b><small>Любые добавленные сотрудники могут создавать офферы в любых документах с любым ответственным.</small></span></label>
                    <button class="btn btn-save" type="submit">Сохранить</button>
                </form>
            </article>
            <div class="settings-side-stack">
                <article class="settings-card connection-settings-card">
                    <div class="settings-card-head"><div><div class="eyebrow">Bitrix24</div><h3>Подключение</h3></div></div>
                    <form data-connection-form><label>Входящий вебхук<input name="webhookUrl" class="custom-input" value="${esc(details.webhookUrl || '')}"></label><div class="button-row separated-actions"><button class="btn btn-save" type="submit">Сохранить подключение</button><button class="btn btn-flat" type="button" data-action="test">Тест</button></div></form>
                </article>
                <article class="settings-card service-settings-card">
                    <div class="eyebrow">Сервис</div><h3>Счётчики</h3>
                    <div class="button-row separated-actions service-actions"><button class="btn btn-flat" data-action="reset-usage">Сбросить счётчик офферов</button><button class="btn btn-flat btn-danger" data-action="delete">Удалить клиента</button></div>
                </article>
            </div>
        </div>`;
}

function renderEmployeesTab(details) {
    const activeUsers = (details.users || []).filter(user => user.active);
    const selected = activeUsers.filter(user => user.offerAccess);
    const available = activeUsers.filter(user => !user.offerAccess);
    const limitReached = selected.length >= details.seatLimit;
    return `
        <article class="settings-card employees-card">
            <div class="settings-card-head employees-head"><div><div class="eyebrow">Сотрудники</div><h3>Доступ к Video Offer</h3></div><button class="btn btn-flat" data-action="sync">Обновить из Bitrix24</button></div>
            <div class="primary-admin-strip"><div><b>Главный администратор клиента</b></div><select id="primary-admin-select" class="custom-input">${renderPrimaryAdminOptions(details)}</select><button class="btn btn-save" data-action="set-primary-admin">Назначить</button></div>
            <div class="employee-pools">
                <section class="employee-pool"><div class="employee-pool-head"><b>Все сотрудники</b><span>${available.length}</span></div><div id="bo-users-available" class="employee-drag-list" data-side="available">${available.map(user => employeeDragCard(user, false)).join('') || '<div class="drag-empty">Нет сотрудников</div>'}</div></section>
                <section class="employee-pool employee-pool-selected"><div class="employee-pool-head"><b>Подключены</b><span>${selected.length} / ${details.seatLimit}</span></div><div id="bo-users-selected" class="employee-drag-list" data-side="selected">${selected.map(user => employeeDragCard(user, true)).join('') || '<div class="drag-empty">Перетащите сотрудника сюда</div>'}</div><div id="bo-seat-limit" class="seat-limit-notice ${limitReached ? 'is-visible' : ''}">Добавлено максимум сотрудников</div></section>
            </div>
        </article>`;
}

function employeeDragCard(user, selected) {
    const controls = selected ? `<div class="employee-card-actions"><button type="button" class="employee-icon-action" data-edit-user="${user.bitrixUserId}" title="Настройки" aria-label="Настройки">${gearIconSvg()}</button><button type="button" class="employee-icon-action employee-remove-action" data-remove-user="${user.bitrixUserId}" title="Удалить" aria-label="Удалить" ${user.primaryAdmin ? 'disabled' : ''}>✕</button></div>` : '';
    return `<div class="employee-drag-item" data-user-id="${user.bitrixUserId}" data-primary="${user.primaryAdmin ? 'true' : 'false'}"><span class="employee-drag-handle" aria-hidden="true">${userIconSvg()}</span><div class="employee-drag-main"><div class="employee-drag-name">${esc(user.displayName)}</div><div class="employee-drag-meta">${esc(user.email || 'Bitrix ID ' + user.bitrixUserId)}${selected ? ` · ${user.offersUsed} офф.` : ''}</div></div>${controls}</div>`;
}

function renderPageTab() {
    if (state.pageTemplateLoadedFor !== state.selectedId) return '<article class="settings-card"><div class="offers-loading">Загрузка шаблона…</div></article>';
    if (!state.pageTemplate) return '<article class="settings-card"><div class="offers-loading">Шаблон страницы недоступен</div></article>';
    return '<div id="bo-page-builder"></div>';
}

function initBackofficePageBuilder() {
    const root = document.getElementById('bo-page-builder');
    if (!root || !state.pageTemplate) return;
    state.pageBuilder = new VideoOfferPageBuilder({
        root,
        theme: 'dark',
        template: state.pageTemplate,
        uploadAsset: (kind, file) => uploadBackofficePageAsset(kind, file),
        saveTemplate: template => api(`/api/backoffice/tenants/${state.selectedId}/page-template`, { method:'PUT', body:JSON.stringify(template) }),
        onSaved: saved => { state.pageTemplate = saved; state.pageTemplateLoadedFor = state.selectedId; }
    });
}

async function uploadBackofficePageAsset(kind, file) {
    const form = new FormData();
    form.append('file', file);
    const response = await fetch(`/api/backoffice/tenants/${state.selectedId}/page-template/assets?kind=${encodeURIComponent(kind)}`, {
        method:'POST',
        headers: backofficeCsrf ? {'X-Backoffice-CSRF': backofficeCsrf} : {},
        body: form
    });
    let data = null;
    try { data = await response.json(); } catch (_) {}
    if (response.status === 401 || response.status === 403) { location.reload(); throw new Error(data?.message || 'Сессия завершена'); }
    if (!response.ok) throw new Error(data?.message || data?.error || `HTTP ${response.status}`);
    return data;
}

function renderOffersTab() {
    if (state.offersLoadedFor !== state.selectedId) return '<article class="settings-card"><div class="offers-loading">Загрузка офферов…</div></article>';
    const offers = sortedFilteredOffers(state.offers);
    return `
        <article class="settings-card offers-card">
            <div class="settings-card-head offers-head"><div><div class="eyebrow">Офферы</div><h3>Актуальные видеоофферы</h3></div><label class="offer-period-control">Период<select id="bo-offer-period" class="custom-input"><option value="7" ${state.offerSort.period === '7' ? 'selected' : ''}>Последние 7 дней</option><option value="all" ${state.offerSort.period === 'all' ? 'selected' : ''}>Все актуальные</option></select></label></div>
            <div class="offers-table-wrap"><table class="offers-table"><thead><tr><th>${sortHeader('createdAt','Дата создания')}</th><th>${sortHeader('documentTypeLabel','Тип документа')}</th><th>ID</th><th>${sortHeader('documentTitle','Название')}</th><th>${sortHeader('authorName','Автор')}</th><th>${sortHeader('viewed','Статус')}</th><th></th></tr></thead><tbody>${offers.map(offerRow).join('') || '<tr><td colspan="7" class="offers-empty">Офферов нет</td></tr>'}</tbody></table></div>
        </article>`;
}

function sortedFilteredOffers(source) {
    let rows = [...(source || [])];
    if (state.offerSort.period === '7') {
        const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
        rows = rows.filter(item => new Date(item.createdAt).getTime() >= cutoff);
    }
    const {key, direction} = state.offerSort;
    const sign = direction === 'desc' ? -1 : 1;
    rows.sort((a,b) => {
        if (key === 'viewed') return (Number(a.viewed) - Number(b.viewed)) * sign;
        if (key === 'createdAt') return (new Date(a.createdAt) - new Date(b.createdAt)) * sign;
        return String(a[key] || '').localeCompare(String(b[key] || ''), 'ru', {sensitivity:'base'}) * sign;
    });
    return rows;
}

function sortHeader(key, label) {
    const active = state.offerSort.key === key;
    const arrow = active ? (state.offerSort.direction === 'asc' ? ' ↑' : ' ↓') : '';
    return `<button class="table-sort" type="button" data-offer-sort="${key}">${label}${arrow}</button>`;
}

function offerRow(item) {
    return `<tr><td class="offer-date-cell">${formatOfferDate(item.createdAt)}</td><td>${esc(item.documentTypeLabel)}</td><td>${item.documentId}</td><td class="offer-title-cell">${esc(item.documentTitle)}</td><td class="offer-author-cell">${esc(item.authorName || '—')}</td><td><span class="view-status ${item.viewed ? 'is-viewed' : 'is-unviewed'}">${item.viewed ? 'Просмотрен' : 'Не просмотрен'}</span></td><td class="offer-open-cell"><a class="btn btn-flat btn-sm" href="${esc(item.documentUrl)}" target="_blank" rel="noopener noreferrer">Открыть документ</a></td></tr>`;
}

function formatOfferDate(value) {
    const date = value ? new Date(value) : null;
    if (!date || Number.isNaN(date.getTime())) return '—';
    return new Intl.DateTimeFormat('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(date);
}

function metric(label, value, hint, progress) {
    return `<article class="metric-card"><div class="metric-label">${esc(label)}</div><div class="metric-value">${esc(value)}</div><div class="metric-hint">${esc(hint)}</div>${progress === null ? '' : `<div class="progress-track"><span style="width:${progress}%"></span></div>`}</article>`;
}

function adminName(details) { return details.users.find(user => user.primaryAdmin)?.displayName || 'Не назначен'; }
function renderPrimaryAdminOptions(details) {
    const active = details.users.filter(user => user.active);
    if (!active.length) return '<option value="">Сотрудники не загружены</option>';
    return active.map(user => `<option value="${user.bitrixUserId}" ${user.primaryAdmin ? 'selected' : ''}>${esc(user.displayName)} · ID ${user.bitrixUserId}</option>`).join('');
}
function userIconSvg() { return `<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21a8 8 0 0 0-16 0"></path><circle cx="12" cy="8" r="4"></circle></svg>`; }
function gearIconSvg() { return `<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h-.1A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.1.36.31.7.6 1 .3.3.68.5 1.1.6h.1v4h-.1c-.42.1-.8.3-1.1.6-.29.3-.5.64-.6 1Z"></path></svg>`; }

async function workspaceClick(event) {
    if (event.target.closest('[data-open-wizard]')) return openWizard();
    const tab = event.target.closest('[data-main-tab]');
    if (tab) return switchMainTab(tab.dataset.mainTab);
    const sort = event.target.closest('[data-offer-sort]');
    if (sort) {
        const key = sort.dataset.offerSort;
        if (state.offerSort.key === key) state.offerSort.direction = state.offerSort.direction === 'asc' ? 'desc' : 'asc';
        else { state.offerSort.key = key; state.offerSort.direction = 'asc'; }
        renderWorkspace();
        return;
    }
    const edit = event.target.closest('[data-edit-user]');
    if (edit) { event.preventDefault(); event.stopPropagation(); return openEmployee(Number(edit.dataset.editUser)); }
    const remove = event.target.closest('[data-remove-user]');
    if (remove) { event.preventDefault(); event.stopPropagation(); return removeEmployeeAccess(Number(remove.dataset.removeUser)); }
    const action = event.target.closest('[data-action]');
    if (!action) return;
    try {
        if (action.dataset.action === 'test') {
            const result = await api(`/api/backoffice/tenants/${state.selectedId}/test`, {method:'POST'});
            alert(`${result.message}\nСотрудников: ${result.usersFound}\nВладелец вебхука: ${result.webhookOwner || '—'}`);
        }
        if (action.dataset.action === 'sync') {
            state.details = await api(`/api/backoffice/tenants/${state.selectedId}/sync-users`, {method:'POST'});
            await refreshList();
            render();
        }
        if (action.dataset.action === 'set-primary-admin') {
            const select = $('primary-admin-select');
            const userId = Number(select?.value);
            if (!userId) throw new Error('Выберите сотрудника');
            state.details = await api(`/api/backoffice/tenants/${state.selectedId}/primary-admin/${userId}`, {method:'PUT'});
            await refreshList();
            render();
        }
        if (action.dataset.action === 'reset-usage') {
            if (confirm('Сбросить счётчики видеоофферов?')) {
                state.details = await api(`/api/backoffice/tenants/${state.selectedId}/reset-usage`, {method:'POST'});
                await refreshList();
                render();
            }
        }
        if (action.dataset.action === 'delete') {
            if (confirm(`Удалить клиента ${state.details.name}?`)) {
                await api(`/api/backoffice/tenants/${state.selectedId}`, {method:'DELETE'});
                state.selectedId = null; state.details = null; state.offers=[]; state.offersLoadedFor=null; state.pageTemplate=null; state.pageTemplateLoadedFor=null; state.pageBuilder=null;
                await loadTenants();
            }
        }
    } catch (error) { alert(error.message); }
}

async function switchMainTab(tab) {
    if (!['settings','employees','offers','page'].includes(tab)) return;
    state.activeTab = tab;
    renderWorkspace();
    if (tab === 'offers' && state.offersLoadedFor !== state.selectedId) {
        try {
            state.offers = await api(`/api/backoffice/tenants/${state.selectedId}/offers`);
            state.offersLoadedFor = state.selectedId;
        } catch (error) {
            state.offers = [];
            state.offersLoadedFor = state.selectedId;
            alert(error.message);
        }
        renderWorkspace();
    }
    if (tab === 'page' && state.pageTemplateLoadedFor !== state.selectedId) {
        try {
            state.pageTemplate = await api(`/api/backoffice/tenants/${state.selectedId}/page-template`);
            state.pageTemplateLoadedFor = state.selectedId;
        } catch (error) {
            state.pageTemplate = null;
            state.pageTemplateLoadedFor = state.selectedId;
            alert(error.message);
        }
        renderWorkspace();
    }
}

function workspaceChange(event) {
    if (event.target.id === 'bo-offer-period') {
        state.offerSort.period = event.target.value;
        renderWorkspace();
    }
}

async function workspaceSubmit(event) {
    event.preventDefault();
    const form = event.target;
    try {
        if (form.matches('[data-tenant-form]')) {
            const values = Object.fromEntries(new FormData(form));
            const details = state.details;
            state.details = await api(`/api/backoffice/tenants/${details.id}`, {
                method:'PUT', body:JSON.stringify({
                    name:values.name, portalDomain:values.portalDomain, webhookUrl:details.webhookUrl,
                    localClientId:details.localClientId, localClientSecret:details.localClientSecret,
                    status:values.status, packageName:values.packageName,
                    seatLimit:Number(values.seatLimit), offerLimit:Number(values.offerLimit),
                    diskQuotaGb:Number(values.diskQuotaGb), retentionDays:Number(values.retentionDays),
                    allowAnyEntity:form.elements.allowAnyEntity.checked
                })
            });
            state.offersLoadedFor = null;
            await refreshList(); render();
        }
        if (form.matches('[data-connection-form]')) {
            const values = Object.fromEntries(new FormData(form));
            const d = state.details;
            state.details = await api(`/api/backoffice/tenants/${d.id}`, {
                method:'PUT', body:JSON.stringify({
                    name:d.name, portalDomain:d.portalDomain, webhookUrl:values.webhookUrl,
                    localClientId:d.localClientId, localClientSecret:d.localClientSecret,
                    status:d.status, packageName:d.packageName, seatLimit:d.seatLimit, offerLimit:d.offerLimit,
                    diskQuotaGb:d.diskQuotaBytes / 1024**3, retentionDays:d.retentionDays,
                    allowAnyEntity:d.allowAnyEntity
                })
            });
            await refreshList(); render();
        }
    } catch (error) { alert(error.message); }
}

function openEmployee(id) {
    const user = state.details.users.find(item => item.bitrixUserId === id);
    if (!user || !user.offerAccess) return;
    $('employee-id').value = id;
    $('employee-modal-title').textContent = user.displayName;
    $('employee-admin').checked = user.admin;
    $('employee-admin').disabled = user.primaryAdmin;
    $('employee-accompanying').value = user.defaultAccompanyingText || '';
    $('employee-message').value = user.defaultClientMessage || '';
    $('employee-modal').classList.remove('d-none');
}
function closeEmployee() { $('employee-modal').classList.add('d-none'); }

async function saveEmployee() {
    const id = Number($('employee-id').value);
    const user = state.details.users.find(item => item.bitrixUserId === id);
    if (!user) return;
    try {
        state.details = await updateSingleUser({
            bitrixUserId:id,
            offerAccess:true,
            admin:user.primaryAdmin ? true : $('employee-admin').checked,
            defaultAccompanyingText:$('employee-accompanying').value,
            defaultClientMessage:$('employee-message').value
        });
        await refreshList(); closeEmployee(); render();
    } catch (error) { alert(error.message); }
}

async function removeEmployeeAccess(id) {
    const user = state.details.users.find(item => item.bitrixUserId === id);
    if (!user || user.primaryAdmin) return;
    try {
        state.details = await updateSingleUser({
            bitrixUserId:id, offerAccess:false, admin:false,
            defaultAccompanyingText:user.defaultAccompanyingText,
            defaultClientMessage:user.defaultClientMessage
        });
        await refreshList(); render();
    } catch (error) { alert(error.message); }
}

async function updateSingleUser(request) {
    return api(`/api/backoffice/tenants/${state.selectedId}/users`, {method:'PUT', body:JSON.stringify({users:[request]})});
}

function initBackofficeEmployeeDragDrop() {
    const available = $('bo-users-available');
    const selected = $('bo-users-selected');
    if (!available || !selected) return;
    bindPhysicalDragGroup('bo-employees', [available, selected], '.employee-drag-item[data-user-id]', {
        activeClass:'is-sorting-employees',
        canStart: ({item, sourceContainer}) => {
            if (sourceContainer === selected && item.dataset.primary === 'true') return false;
            if (sourceContainer === available && state.details.seatsUsed >= state.details.seatLimit) {
                signalSeatLimit(item, 'bo-seat-limit');
                return false;
            }
            return true;
        },
        onDrop: async ({item, sourceContainer, targetContainer}) => {
            if (!item || sourceContainer === targetContainer) return;
            const id = Number(item.dataset.userId);
            const user = state.details.users.find(entry => entry.bitrixUserId === id);
            if (!user) return render();
            const enable = targetContainer === selected;
            if (enable && state.details.seatsUsed >= state.details.seatLimit) {
                signalSeatLimit(item, 'bo-seat-limit');
                return render();
            }
            try {
                state.details = await updateSingleUser({
                    bitrixUserId:id,
                    offerAccess:enable,
                    admin:enable ? user.admin : false,
                    defaultAccompanyingText:user.defaultAccompanyingText,
                    defaultClientMessage:user.defaultClientMessage
                });
                await refreshList(); render();
            } catch (error) { alert(error.message); await loadDetails(); render(); }
        }
    });
}

function signalSeatLimit(item, noticeId) {
    item?.classList.remove('drag-denied');
    void item?.offsetWidth;
    item?.classList.add('drag-denied');
    const notice = $(noticeId);
    if (!notice) return;
    notice.classList.add('is-visible','is-flashing');
    setTimeout(() => notice.classList.remove('is-flashing'), 420);
}

const dragGroups = {};
function bindPhysicalDragGroup(key, containers, selector, options={}) {
    let group = dragGroups[key];
    if (!group) {
        group = {key, containers:[], selector, options, bound:new WeakSet(), pointerId:null, item:null, placeholder:null, sourceContainer:null, activeContainer:null, startX:0, startY:0, offsetX:0, offsetY:0, dragging:false};
        dragGroups[key] = group;
    }
    group.containers = containers;
    group.selector = selector;
    group.options = options;
    containers.forEach(container => {
        if (group.bound.has(container)) return;
        group.bound.add(container);
        container.addEventListener('pointerdown', event => dragPointerDown(event, group));
    });
}
function dragPointerDown(event, group) {
    if (event.button !== undefined && event.button !== 0) return;
    if (event.target.closest('button,input,select,textarea,a')) return;
    const item = event.target.closest(group.selector);
    if (!item) return;
    const sourceContainer = item.parentElement;
    if (!group.containers.includes(sourceContainer)) return;
    if (typeof group.options.canStart === 'function' && !group.options.canStart({item, sourceContainer})) { event.preventDefault(); return; }
    Object.assign(group,{pointerId:event.pointerId,item,sourceContainer,activeContainer:sourceContainer,startX:event.clientX,startY:event.clientY,dragging:false});
    item.setPointerCapture?.(event.pointerId);
    const move = e => dragPointerMove(e,group,move,up);
    const up = e => dragPointerUp(e,group,move,up);
    document.addEventListener('pointermove',move,{passive:false});
    document.addEventListener('pointerup',up,{passive:false});
    document.addEventListener('pointercancel',up,{passive:false});
}
function dragPointerMove(event,group,move,up) {
    if (group.pointerId !== null && event.pointerId !== group.pointerId) return;
    if (!group.dragging) {
        if (Math.hypot(event.clientX-group.startX,event.clientY-group.startY) < 4) return;
        startPhysicalDrag(event,group);
    }
    event.preventDefault();
    group.item.style.left=(event.clientX-group.offsetX)+'px';
    group.item.style.top=(event.clientY-group.offsetY)+'px';
    updateDragPlaceholder(event,group);
    autoscrollDrag(event,group);
}
function startPhysicalDrag(event,group) {
    const item=group.item, rect=item.getBoundingClientRect();
    group.offsetX=event.clientX-rect.left; group.offsetY=event.clientY-rect.top;
    const placeholder=document.createElement('div');
    placeholder.className='physical-drag-placeholder'; placeholder.style.width=rect.width+'px'; placeholder.style.height=rect.height+'px';
    item.parentElement.insertBefore(placeholder,item); group.placeholder=placeholder;
    item.classList.add('physical-drag-floating');
    for (const [k,v] of Object.entries({position:'fixed',left:rect.left+'px',top:rect.top+'px',width:rect.width+'px','min-width':rect.width+'px','max-width':rect.width+'px',height:rect.height+'px','z-index':'5000',margin:'0'})) item.style.setProperty(k,v,'important');
    document.body.appendChild(item); group.dragging=true;
    group.containers.forEach(c=>c.classList.add(group.options.activeClass||'is-physical-dragging'));
    document.body.classList.add('is-physical-dragging');
}
function updateDragPlaceholder(event,group) {
    const target=dragTargetContainer(event.clientX,event.clientY,group); if(!target||!group.placeholder)return;
    const items=[...target.querySelectorAll(group.selector)].filter(el=>el!==group.item);
    let before=null;
    for(const child of items){const r=child.getBoundingClientRect();if(event.clientY<r.top+r.height/2){before=child;break;}}
    if (!before) before = target.querySelector('.drag-empty');
    target.insertBefore(group.placeholder,before); group.activeContainer=target;
}
function dragTargetContainer(x,y,group) {
    for(const el of document.elementsFromPoint(x,y)) for(const c of group.containers) if(el===c||c.contains(el)) return c;
    for(const c of group.containers){const r=c.getBoundingClientRect();if(x>=r.left-20&&x<=r.right+20&&y>=r.top-28&&y<=r.bottom+28)return c;}
    return group.activeContainer||group.sourceContainer;
}
function autoscrollDrag(event,group) {
    let host=group.activeContainer;
    while(host&&host!==document.body){const style=getComputedStyle(host);if(/(auto|scroll)/.test(style.overflowY)&&host.scrollHeight>host.clientHeight)break;host=host.parentElement;}
    if(!host||host===document.body)host=document.scrollingElement||document.documentElement;
    const r=host.getBoundingClientRect(),edge=54; if(event.clientY<r.top+edge)host.scrollTop-=12;else if(event.clientY>r.bottom-edge)host.scrollTop+=12;
}
function dragPointerUp(event,group,move,up) {
    document.removeEventListener('pointermove',move);document.removeEventListener('pointerup',up);document.removeEventListener('pointercancel',up);
    if(!group.dragging){resetDrag(group);return;}
    event.preventDefault();
    const item=group.item, placeholder=group.placeholder, target=placeholder?.parentElement||group.sourceContainer;
    if(item&&placeholder&&target){target.insertBefore(item,placeholder);placeholder.remove();}
    cleanupFloating(item);
    group.containers.forEach(c=>c.classList.remove(group.options.activeClass||'is-physical-dragging'));
    document.body.classList.remove('is-physical-dragging');
    if(typeof group.options.onDrop==='function') group.options.onDrop({item,sourceContainer:group.sourceContainer,targetContainer:target,containers:group.containers});
    resetDrag(group);
}
function cleanupFloating(item){if(!item)return;item.classList.remove('physical-drag-floating');['position','left','top','width','min-width','max-width','height','z-index','margin'].forEach(k=>item.style.removeProperty(k));}
function resetDrag(group){Object.assign(group,{pointerId:null,item:null,placeholder:null,sourceContainer:null,activeContainer:null,startX:0,startY:0,offsetX:0,offsetY:0,dragging:false});}

function openWizard() {
    abortWizardTest();
    state.wizard=createWizardState();
    ['w-name','w-domain','w-webhook'].forEach(id=>$(id).value='');
    $('w-package').value='Beta'; $('w-seats').value='3'; $('w-offers').value='50'; $('w-disk').value='10'; $('w-retention').value='7'; $('w-any-entity').checked=false;
    $('wizard-error').hidden=true; $('client-modal').classList.remove('d-none'); resetConnectionChecks(); renderWizard();
}
function closeWizard(){ $('client-modal').classList.add('d-none'); }
async function cancelWizard(){
    abortWizardTest(); const id=state.wizard?.tenantId;
    if(id&&!state.wizard.finished){if(!confirm('Отменить добавление клиента?'))return;try{await api(`/api/backoffice/tenants/${id}`,{method:'DELETE'});}catch(error){showWizardError('Не удалось удалить клиента: '+error.message);return;}if(state.selectedId===id){state.selectedId=null;state.details=null;}await refreshList();render();}
    closeWizard(); state.wizard=createWizardState();
}
function renderWizard(){
    const w=state.wizard;[1,2,3].forEach(step=>$(`wizard-step-${step}`).classList.toggle('d-none',w.step!==step));$('wizard-step-label').textContent=`Шаг ${w.step} из 3`;$('wizard-back').classList.toggle('d-none',w.step===1);$('wizard-next').classList.toggle('d-none',w.step===3);$('wizard-finish').classList.toggle('d-none',w.step!==3);$('wizard-next').disabled=w.testRunning||(w.step===2&&!w.testOk);$('retry-test').disabled=w.testRunning;if(w.step===3)renderWizardUsers();
}
function wizardCreatePayload(){return{name:$('w-name').value,portalDomain:$('w-domain').value,webhookUrl:$('w-webhook').value,localClientId:null,localClientSecret:null,packageName:$('w-package').value||'Beta',seatLimit:Number($('w-seats').value)||3,offerLimit:Number($('w-offers').value)||50,diskQuotaGb:Number($('w-disk').value)||10,retentionDays:Number($('w-retention').value)||7,allowAnyEntity:$('w-any-entity').checked};}
async function persistWizardDraft(){const payload=wizardCreatePayload();if(!state.wizard.tenantId)return api('/api/backoffice/tenants',{method:'POST',body:JSON.stringify(payload)});return api(`/api/backoffice/tenants/${state.wizard.tenantId}`,{method:'PUT',body:JSON.stringify({...payload,status:'PENDING'})});}
async function wizardNext(){hideWizardError();try{if(state.wizard.step===1){state.wizard.testOk=false;const details=await persistWizardDraft();state.wizard.tenantId=details.id;state.wizard.step=2;resetConnectionChecks();renderWizard();await runConnectionTest();return;}if(state.wizard.step===2){if(!state.wizard.testOk)throw new Error('Подключение не проверено');state.wizard.step=3;renderWizard();await wizardSyncUsers();}}catch(error){showWizardError(error.message);}}
function wizardBack(){if(state.wizard.step<=1)return;abortWizardTest();state.wizard.step-=1;renderWizard();}
function abortWizardTest(){if(state.wizard?.testController){state.wizard.testController.abort();state.wizard.testController=null;}if(state.wizard)state.wizard.testRunning=false;}
function resetConnectionChecks(){const banner=$('connection-banner');banner.className='wizard-banner is-loading';banner.textContent='Проверяем подключение…';['check-webhook','check-crm','check-users'].forEach(id=>{const el=$(id);el.className='setup-check is-pending';el.querySelector('span').textContent='•';});}
async function runConnectionTest(){
    if(!state.wizard.tenantId)return;abortWizardTest();const w=state.wizard,controller=new AbortController(),seq=++w.testSeq;w.testController=controller;w.testRunning=true;w.testOk=false;resetConnectionChecks();renderWizard();let timedOut=false;const timeout=setTimeout(()=>{timedOut=true;controller.abort();},25000);
    try{const result=await api(`/api/backoffice/tenants/${w.tenantId}/test`,{method:'POST',signal:controller.signal});if(seq!==state.wizard.testSeq||state.wizard.step!==2)return;w.testOk=Boolean(result.ok);const banner=$('connection-banner');banner.className=result.ok?'wizard-banner is-ok':'wizard-banner is-error';banner.textContent=result.ok?`Подключение работает. Сотрудников: ${result.usersFound}`:(result.message||'Проверка не пройдена');['check-webhook','check-crm','check-users'].forEach(id=>{const el=$(id);el.className=result.ok?'setup-check is-ok':'setup-check is-error';el.querySelector('span').textContent=result.ok?'✓':'!';});}
    catch(error){if(seq!==state.wizard.testSeq||state.wizard.step!==2)return;w.testOk=false;const banner=$('connection-banner');banner.className='wizard-banner is-error';banner.textContent=timedOut?'Bitrix24 не ответил. Нажмите «Проверить ещё раз».':error.message;['check-webhook','check-crm','check-users'].forEach(id=>{const el=$(id);el.className='setup-check is-error';el.querySelector('span').textContent='!';});}
    finally{clearTimeout(timeout);if(seq===state.wizard.testSeq){w.testRunning=false;w.testController=null;renderWizard();}}
}
async function wizardSyncUsers(){if(!state.wizard.tenantId)return;try{const d=await api(`/api/backoffice/tenants/${state.wizard.tenantId}/sync-users`,{method:'POST'});state.wizard.users=d.users;renderWizardUsers();}catch(error){showWizardError(error.message);}}
function renderWizardUsers(){const el=$('w-users');if(!state.wizard.users.length){el.innerHTML='<div class="history-empty">Сотрудники не загружены.</div>';return;}el.innerHTML=state.wizard.users.map(user=>`<label class="wizard-user"><input type="radio" name="wizard-admin" value="${user.bitrixUserId}"><span><b>${esc(user.displayName)}</b><small>${esc(user.email||'ID '+user.bitrixUserId)}</small></span><small>ID ${user.bitrixUserId}</small></label>`).join('');}
async function wizardFinish(){hideWizardError();try{const id=state.wizard.tenantId;if(!id)throw new Error('Клиент не создан');const chosen=document.querySelector('input[name="wizard-admin"]:checked');if(!chosen)throw new Error('Выберите главного администратора клиента');const current=await api(`/api/backoffice/tenants/${id}`);const payload={name:current.name,portalDomain:current.portalDomain,webhookUrl:current.webhookUrl,localClientId:current.localClientId,localClientSecret:current.localClientSecret,packageName:$('w-package').value,seatLimit:Number($('w-seats').value),offerLimit:Number($('w-offers').value),diskQuotaGb:Number($('w-disk').value),retentionDays:Number($('w-retention').value),allowAnyEntity:$('w-any-entity').checked};await api(`/api/backoffice/tenants/${id}`,{method:'PUT',body:JSON.stringify({...payload,status:'PENDING'})});await api(`/api/backoffice/tenants/${id}/primary-admin/${chosen.value}`,{method:'PUT'});await api(`/api/backoffice/tenants/${id}`,{method:'PUT',body:JSON.stringify({...payload,status:'ACTIVE'})});state.wizard.finished=true;state.selectedId=id;state.activeTab='settings';closeWizard();await loadTenants();}catch(error){showWizardError(error.message);}}
function showWizardError(text){$('wizard-error').textContent=text;$('wizard-error').hidden=false;}
function hideWizardError(){$('wizard-error').hidden=true;}
