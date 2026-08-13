const state = {
    tenants: [],
    selectedId: null,
    details: null,
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
const pct = (a, b) => b ? Math.min(100, Math.round(a * 100 / b)) : 0;
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
        await api('/api/backoffice/logout', {method: 'POST'});
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
        await loadDetails();
        render();
    };
    $('workspace').onclick = workspaceClick;
    $('workspace').addEventListener('submit', workspaceSubmit);
}

async function api(url, options = {}) {
    pendingRequests += 1;
    setStatus('Работаю…');
    const method = String(options.method || 'GET').toUpperCase();
    const headers = {'Content-Type': 'application/json', ...(options.headers || {})};
    if (method !== 'GET' && method !== 'HEAD' && backofficeCsrf) {
        headers['X-Backoffice-CSRF'] = backofficeCsrf;
    }
    try {
        const response = await fetch(url, {...options, headers});
        let data = null;
        try { data = await response.json(); } catch (_) {}
        if (response.status === 401) {
            location.reload();
            throw new Error('Сессия завершена');
        }
        if (response.status === 403) {
            location.reload();
            throw new Error(data?.message || 'Сессия устарела');
        }
        if (!response.ok) {
            throw new Error(data?.message || data?.error || `HTTP ${response.status}`);
        }
        return data;
    } finally {
        pendingRequests = Math.max(0, pendingRequests - 1);
        if (pendingRequests === 0) setStatus('Готово');
    }
}

function setStatus(text) {
    $('global-status').textContent = text;
}

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

async function loadDetails() {
    state.details = state.selectedId ? await api(`/api/backoffice/tenants/${state.selectedId}`) : null;
}

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
    const disk = pct(details.diskUsedBytes, details.diskQuotaBytes);
    const offers = pct(details.offersUsed, details.offerLimit);
    const seats = pct(details.seatsUsed, details.seatLimit);
    workspace.innerHTML = `
<div class="workspace-heading"><div><div class="eyebrow">Клиент · ${esc(details.packageName)}</div><h1 class="workspace-title">${esc(details.name)}</h1><div class="workspace-meta">${esc(details.portalDomain)} · member_id ${esc(details.memberId || '—')}</div></div><div class="workspace-actions"><span class="status-pill ${String(details.status).toLowerCase()}">${esc(details.status)}</span><button class="btn btn-flat" data-action="test">Проверить подключение</button><button class="btn btn-flat" data-action="sync">Сотрудники из Bitrix</button></div></div>
<div class="metrics-grid">
${metric('Сотрудники', `${details.seatsUsed} / ${details.seatLimit}`, `Свободно ${Math.max(0, details.seatLimit - details.seatsUsed)}`, seats)}
${metric('Видеоофферы', `${details.offersUsed} / ${details.offerLimit}`, `Осталось ${Math.max(0, details.offerLimit - details.offersUsed)}`, offers)}
${metric('Диск', `${fmtBytes(details.diskUsedBytes)} / ${fmtBytes(details.diskQuotaBytes)}`, `Свободно ${fmtBytes(Math.max(0, details.diskQuotaBytes - details.diskUsedBytes))}`, disk)}
${metric('Главный админ', adminName(details), details.allowAnyEntity ? 'Любые документы CRM' : 'Только свои документы', null)}
</div>
<div class="settings-grid">
<article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">Компания</div><h3>Пакет и доступ</h3></div></div><form data-tenant-form>
<div class="two-col-form"><label>Название<input name="name" class="custom-input" value="${esc(details.name)}"></label><label>Портал<input name="portalDomain" class="custom-input" value="${esc(details.portalDomain)}"></label><label>Пакет<input name="packageName" class="custom-input" value="${esc(details.packageName)}"></label><label>Статус<select name="status" class="custom-input"><option ${details.status === 'ACTIVE' ? 'selected' : ''}>ACTIVE</option><option ${details.status === 'DISABLED' ? 'selected' : ''}>DISABLED</option><option ${details.status === 'PENDING' ? 'selected' : ''}>PENDING</option></select></label><label>Лимит сотрудников<input name="seatLimit" type="number" min="1" class="custom-input" value="${details.seatLimit}"></label><label>Лимит офферов<input name="offerLimit" type="number" min="1" class="custom-input" value="${details.offerLimit}"></label><label>Диск, ГБ<input name="diskQuotaGb" type="number" step=".1" min=".1" class="custom-input" value="${(details.diskQuotaBytes / 1024 ** 3).toFixed(2)}"></label></div>
<label class="toggle-card"><input name="allowAnyEntity" type="checkbox" ${details.allowAnyEntity ? 'checked' : ''}><span><b>Разрешить любые документы CRM</b></span></label>
<button class="btn btn-save" type="submit">Сохранить</button></form></article>
<article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">Bitrix24</div><h3>Подключение</h3></div></div><form data-connection-form>
<label>Входящий вебхук<input name="webhookUrl" class="custom-input" value="${esc(details.webhookUrl || '')}"></label><div class="button-row"><button class="btn btn-save" type="submit">Сохранить подключение</button><button class="btn btn-flat" type="button" data-action="test">Тест</button></div></form></article>
<article class="settings-card full-card"><div class="settings-card-head"><div><div class="eyebrow">Сотрудники</div><h3>Доступ, роли и шаблоны</h3></div><div class="button-row"><button class="btn btn-flat" data-action="sync">Обновить из Bitrix24</button></div></div>
<div class="primary-admin-strip"><div><b>Главный администратор клиента</b></div><select id="primary-admin-select" class="custom-input">${renderPrimaryAdminOptions(details)}</select><button class="btn btn-save" data-action="set-primary-admin">Назначить</button></div>
<div class="users-list">${renderUsers(details)}</div></article>
<article class="settings-card danger-zone"><div class="eyebrow">Сервис</div><h3>Счётчики</h3><div class="button-row"><button class="btn btn-flat" data-action="reset-usage">Сбросить счётчик офферов</button><button class="btn btn-flat btn-danger" data-action="delete">Удалить клиента</button></div></article>
</div>`;
}

function metric(label, value, hint, progress) {
    return `<article class="metric-card"><div class="metric-label">${esc(label)}</div><div class="metric-value">${esc(value)}</div><div class="metric-hint">${esc(hint)}</div>${progress === null ? '' : `<div class="progress-track"><span style="width:${progress}%"></span></div>`}</article>`;
}

function adminName(details) {
    return details.users.find(user => user.primaryAdmin)?.displayName || 'Не назначен';
}

function renderPrimaryAdminOptions(details) {
    const active = details.users.filter(user => user.active);
    if (!active.length) return '<option value="">Синхронизируйте сотрудников</option>';
    return active.map(user => `<option value="${user.bitrixUserId}" ${user.primaryAdmin ? 'selected' : ''}>${esc(user.displayName)} · ID ${user.bitrixUserId}</option>`).join('');
}

function initials(name) {
    return String(name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]).join('').toUpperCase();
}

function renderUsers(details) {
    if (!details.users.length) return '<div class="history-empty p">Сотрудники не синхронизированы.</div>';
    return details.users.map(user => `<div class="user-row" data-user-id="${user.bitrixUserId}"><div class="user-avatar">${esc(initials(user.displayName))}</div><div><b>${esc(user.displayName)}</b><small>${esc(user.email || 'ID ' + user.bitrixUserId)}</small></div><div class="user-role">${user.primaryAdmin ? 'Главный админ' : user.admin ? 'Администратор' : user.offerAccess ? 'Пользователь' : 'Нет доступа'}</div><div class="user-used"><b>${user.offersUsed}</b><small>офферов</small></div><div class="user-actions"><button class="btn btn-flat btn-sm" data-edit-user="${user.bitrixUserId}">Настроить</button></div></div>`).join('');
}

async function workspaceClick(event) {
    if (event.target.closest('[data-open-wizard]')) return openWizard();
    const action = event.target.closest('[data-action]');
    if (action) {
        try {
            if (action.dataset.action === 'test') {
                const result = await api(`/api/backoffice/tenants/${state.selectedId}/test`, {method: 'POST'});
                alert(`${result.message}\nСотрудников: ${result.usersFound}\nВладелец вебхука: ${result.webhookOwner || '—'}`);
            }
            if (action.dataset.action === 'sync') {
                state.details = await api(`/api/backoffice/tenants/${state.selectedId}/sync-users`, {method: 'POST'});
                await refreshList();
                render();
            }
            if (action.dataset.action === 'set-primary-admin') {
                const select = $('primary-admin-select');
                const userId = Number(select?.value);
                if (!userId) throw new Error('Выберите сотрудника');
                if (confirm(`Назначить ${select.options[select.selectedIndex].text} главным администратором?`)) {
                    state.details = await api(`/api/backoffice/tenants/${state.selectedId}/primary-admin/${userId}`, {method: 'PUT'});
                    await refreshList();
                    render();
                }
            }
            if (action.dataset.action === 'reset-usage') {
                if (confirm('Сбросить счётчики видеоофферов?')) {
                    state.details = await api(`/api/backoffice/tenants/${state.selectedId}/reset-usage`, {method: 'POST'});
                    await refreshList();
                    render();
                }
            }
            if (action.dataset.action === 'delete') {
                if (confirm(`Удалить клиента ${state.details.name}?`)) {
                    await api(`/api/backoffice/tenants/${state.selectedId}`, {method: 'DELETE'});
                    state.selectedId = null;
                    state.details = null;
                    await loadTenants();
                }
            }
        } catch (error) {
            alert(error.message);
        }
        return;
    }
    const edit = event.target.closest('[data-edit-user]');
    if (edit) openEmployee(Number(edit.dataset.editUser));
}

async function workspaceSubmit(event) {
    event.preventDefault();
    const form = event.target;
    try {
        if (form.matches('[data-tenant-form]')) {
            const values = Object.fromEntries(new FormData(form));
            const details = state.details;
            state.details = await api(`/api/backoffice/tenants/${details.id}`, {
                method: 'PUT',
                body: JSON.stringify({
                    name: values.name,
                    portalDomain: values.portalDomain,
                    webhookUrl: details.webhookUrl,
                    localClientId: details.localClientId,
                    localClientSecret: details.localClientSecret,
                    status: values.status,
                    packageName: values.packageName,
                    seatLimit: Number(values.seatLimit),
                    offerLimit: Number(values.offerLimit),
                    diskQuotaGb: Number(values.diskQuotaGb),
                    allowAnyEntity: form.elements.allowAnyEntity.checked
                })
            });
            await refreshList();
            render();
        }
        if (form.matches('[data-connection-form]')) {
            const values = Object.fromEntries(new FormData(form));
            const details = state.details;
            state.details = await api(`/api/backoffice/tenants/${details.id}`, {
                method: 'PUT',
                body: JSON.stringify({
                    name: details.name,
                    portalDomain: details.portalDomain,
                    webhookUrl: values.webhookUrl,
                    localClientId: details.localClientId,
                    localClientSecret: details.localClientSecret,
                    status: details.status,
                    packageName: details.packageName,
                    seatLimit: details.seatLimit,
                    offerLimit: details.offerLimit,
                    diskQuotaGb: details.diskQuotaBytes / 1024 ** 3,
                    allowAnyEntity: details.allowAnyEntity
                })
            });
            await refreshList();
            render();
        }
    } catch (error) {
        alert(error.message);
    }
}

async function refreshList() {
    state.tenants = await api('/api/backoffice/tenants');
}

function openWizard() {
    abortWizardTest();
    state.wizard = createWizardState();
    ['w-name', 'w-domain', 'w-webhook'].forEach(id => $(id).value = '');
    $('w-package').value = 'Beta';
    $('w-seats').value = '3';
    $('w-offers').value = '50';
    $('w-disk').value = '10';
    $('w-any-entity').checked = false;
    $('wizard-error').hidden = true;
    $('client-modal').classList.remove('d-none');
    resetConnectionChecks();
    renderWizard();
}

function closeWizard() {
    $('client-modal').classList.add('d-none');
}

async function cancelWizard() {
    abortWizardTest();
    const id = state.wizard?.tenantId;
    if (id && !state.wizard.finished) {
        if (!confirm('Отменить добавление клиента?')) return;
        try {
            await api(`/api/backoffice/tenants/${id}`, {method: 'DELETE'});
        } catch (error) {
            showWizardError('Не удалось удалить клиента: ' + error.message);
            return;
        }
        if (state.selectedId === id) {
            state.selectedId = null;
            state.details = null;
        }
        await refreshList();
        render();
    }
    closeWizard();
    state.wizard = createWizardState();
}

function renderWizard() {
    const wizard = state.wizard;
    [1, 2, 3].forEach(step => $(`wizard-step-${step}`).classList.toggle('d-none', wizard.step !== step));
    $('wizard-step-label').textContent = `Шаг ${wizard.step} из 3`;
    $('wizard-back').classList.toggle('d-none', wizard.step === 1);
    $('wizard-next').classList.toggle('d-none', wizard.step === 3);
    $('wizard-finish').classList.toggle('d-none', wizard.step !== 3);
    $('wizard-next').disabled = wizard.testRunning || (wizard.step === 2 && !wizard.testOk);
    $('wizard-back').disabled = false;
    $('retry-test').disabled = wizard.testRunning;
    if (wizard.step === 3) renderWizardUsers();
}

function wizardCreatePayload() {
    return {
        name: $('w-name').value,
        portalDomain: $('w-domain').value,
        webhookUrl: $('w-webhook').value,
        localClientId: null,
        localClientSecret: null,
        packageName: $('w-package').value || 'Beta',
        seatLimit: Number($('w-seats').value) || 3,
        offerLimit: Number($('w-offers').value) || 50,
        diskQuotaGb: Number($('w-disk').value) || 10,
        allowAnyEntity: $('w-any-entity').checked
    };
}

async function persistWizardDraft() {
    const payload = wizardCreatePayload();
    if (!state.wizard.tenantId) {
        return api('/api/backoffice/tenants', {method: 'POST', body: JSON.stringify(payload)});
    }
    return api(`/api/backoffice/tenants/${state.wizard.tenantId}`, {
        method: 'PUT',
        body: JSON.stringify({...payload, status: 'PENDING'})
    });
}

async function wizardNext() {
    hideWizardError();
    try {
        if (state.wizard.step === 1) {
            state.wizard.testOk = false;
            const details = await persistWizardDraft();
            state.wizard.tenantId = details.id;
            state.wizard.step = 2;
            resetConnectionChecks();
            renderWizard();
            await runConnectionTest();
            return;
        }
        if (state.wizard.step === 2) {
            if (!state.wizard.testOk) throw new Error('Подключение не проверено');
            state.wizard.step = 3;
            renderWizard();
            await wizardSyncUsers();
        }
    } catch (error) {
        showWizardError(error.message);
    }
}

function wizardBack() {
    if (state.wizard.step <= 1) return;
    abortWizardTest();
    state.wizard.step -= 1;
    renderWizard();
}

function abortWizardTest() {
    if (state.wizard?.testController) {
        state.wizard.testController.abort();
        state.wizard.testController = null;
    }
    if (state.wizard) state.wizard.testRunning = false;
}

function resetConnectionChecks() {
    const banner = $('connection-banner');
    banner.className = 'wizard-banner is-loading';
    banner.textContent = 'Проверяем подключение…';
    ['check-webhook', 'check-crm', 'check-users'].forEach(id => {
        const element = $(id);
        element.className = 'setup-check is-pending';
        element.querySelector('span').textContent = '•';
    });
}

async function runConnectionTest() {
    if (!state.wizard.tenantId) return;
    abortWizardTest();
    const wizard = state.wizard;
    const controller = new AbortController();
    const seq = ++wizard.testSeq;
    wizard.testController = controller;
    wizard.testRunning = true;
    wizard.testOk = false;
    resetConnectionChecks();
    renderWizard();
    let timedOut = false;
    const timeout = setTimeout(() => {
        timedOut = true;
        controller.abort();
    }, 25000);
    try {
        const result = await api(`/api/backoffice/tenants/${wizard.tenantId}/test`, {
            method: 'POST',
            signal: controller.signal
        });
        if (seq !== state.wizard.testSeq || state.wizard.step !== 2) return;
        state.wizard.testOk = Boolean(result.ok);
        const banner = $('connection-banner');
        banner.className = result.ok ? 'wizard-banner is-ok' : 'wizard-banner is-error';
        banner.textContent = result.ok ? `Подключение работает. Сотрудников: ${result.usersFound}` : (result.message || 'Проверка не пройдена');
        ['check-webhook', 'check-crm', 'check-users'].forEach(id => {
            const element = $(id);
            element.className = result.ok ? 'setup-check is-ok' : 'setup-check is-error';
            element.querySelector('span').textContent = result.ok ? '✓' : '!';
        });
    } catch (error) {
        if (seq !== state.wizard.testSeq || state.wizard.step !== 2) return;
        state.wizard.testOk = false;
        const banner = $('connection-banner');
        banner.className = 'wizard-banner is-error';
        banner.textContent = timedOut ? 'Bitrix24 не ответил. Нажмите «Проверить ещё раз».' : error.message;
        ['check-webhook', 'check-crm', 'check-users'].forEach(id => {
            const element = $(id);
            element.className = 'setup-check is-error';
            element.querySelector('span').textContent = '!';
        });
    } finally {
        clearTimeout(timeout);
        if (seq === state.wizard.testSeq) {
            state.wizard.testRunning = false;
            state.wizard.testController = null;
            renderWizard();
        }
    }
}

async function wizardSyncUsers() {
    if (!state.wizard.tenantId) return;
    try {
        const details = await api(`/api/backoffice/tenants/${state.wizard.tenantId}/sync-users`, {method: 'POST'});
        state.wizard.users = details.users;
        renderWizardUsers();
    } catch (error) {
        showWizardError(error.message);
    }
}

function renderWizardUsers() {
    const element = $('w-users');
    if (!state.wizard.users.length) {
        element.innerHTML = '<div class="history-empty">Сотрудники не загружены.</div>';
        return;
    }
    element.innerHTML = state.wizard.users.filter(user => user.active).map(user => `<label class="wizard-user"><input type="radio" name="wizard-admin" value="${user.bitrixUserId}"><span><b>${esc(user.displayName)}</b><small>${esc(user.email || 'ID ' + user.bitrixUserId)}</small></span><small>ID ${user.bitrixUserId}</small></label>`).join('');
}

async function wizardFinish() {
    hideWizardError();
    try {
        const id = state.wizard.tenantId;
        if (!id) throw new Error('Клиент не создан');
        const chosen = document.querySelector('input[name="wizard-admin"]:checked');
        if (!chosen) throw new Error('Выберите главного администратора клиента');
        const current = await api(`/api/backoffice/tenants/${id}`);
        const packagePayload = {
            name: current.name,
            portalDomain: current.portalDomain,
            webhookUrl: current.webhookUrl,
            localClientId: current.localClientId,
            localClientSecret: current.localClientSecret,
            packageName: $('w-package').value,
            seatLimit: Number($('w-seats').value),
            offerLimit: Number($('w-offers').value),
            diskQuotaGb: Number($('w-disk').value),
            allowAnyEntity: $('w-any-entity').checked
        };
        await api(`/api/backoffice/tenants/${id}`, {
            method: 'PUT',
            body: JSON.stringify({...packagePayload, status: 'PENDING'})
        });
        await api(`/api/backoffice/tenants/${id}/primary-admin/${chosen.value}`, {method: 'PUT'});
        await api(`/api/backoffice/tenants/${id}`, {
            method: 'PUT',
            body: JSON.stringify({...packagePayload, status: 'ACTIVE'})
        });
        state.wizard.finished = true;
        state.selectedId = id;
        closeWizard();
        await loadTenants();
    } catch (error) {
        showWizardError(error.message);
    }
}

function showWizardError(text) {
    $('wizard-error').textContent = text;
    $('wizard-error').hidden = false;
}

function hideWizardError() {
    $('wizard-error').hidden = true;
}

function openEmployee(id) {
    const user = state.details.users.find(item => item.bitrixUserId === id);
    if (!user) return;
    $('employee-id').value = id;
    $('employee-modal-title').textContent = user.displayName;
    $('employee-access').checked = user.offerAccess;
    $('employee-admin').checked = user.admin;
    $('employee-access').disabled = user.primaryAdmin;
    $('employee-admin').disabled = user.primaryAdmin;
    $('employee-accompanying').value = user.defaultAccompanyingText || '';
    $('employee-message').value = user.defaultClientMessage || '';
    $('employee-modal').classList.remove('d-none');
}

function closeEmployee() {
    $('employee-modal').classList.add('d-none');
}

async function saveEmployee() {
    const id = Number($('employee-id').value);
    const user = state.details.users.find(item => item.bitrixUserId === id);
    if (!user) return;
    const request = {
        bitrixUserId: id,
        offerAccess: user.primaryAdmin ? true : $('employee-access').checked,
        admin: user.primaryAdmin ? true : $('employee-admin').checked,
        defaultAccompanyingText: $('employee-accompanying').value,
        defaultClientMessage: $('employee-message').value
    };
    try {
        state.details = await api(`/api/backoffice/tenants/${state.selectedId}/users`, {
            method: 'PUT',
            body: JSON.stringify({users: [request]})
        });
        await refreshList();
        closeEmployee();
        render();
    } catch (error) {
        alert(error.message);
    }
}
