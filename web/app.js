const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
const DRIVE_FILES = "https://www.googleapis.com/drive/v3/files";
const DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
const FOLDER_MIME = "application/vnd.google-apps.folder";
const MARKDOWN_MIME = "text/markdown";

const elements = Object.fromEntries([
  "client-id", "client-id-config", "authorize", "revoke", "auth-status", "connection", "sync",
  "sync-indicator", "open-search", "close-search", "clear-search", "header-search", "app-title",
  "open-settings", "settings", "log", "new-body", "create", "create-status", "search",
  "list-status", "timeline", "editor", "edit-body", "edit-status", "save", "delete",
].map((id) => [id.replaceAll("-", "_"), document.querySelector(`#${id}`)]));

let tokenClient;
let accessToken;
let accessTokenExpiresAt = 0;
let accessTokenExpiryTimer;
let pendingAction;
let notes = [];
let selected = null;
let busy = false;
let busyIndicatorTimer;

class AuthorizationExpiredError extends Error {}

const configuredClientId = window.SOLIMEMO_CONFIG?.googleClientId?.trim() || "";
elements.client_id.value = configuredClientId || localStorage.getItem("solimemo.webClientId") || "";
elements.client_id_config.hidden = Boolean(configuredClientId);

function log(message) {
  const time = new Date().toLocaleTimeString("ja-JP");
  elements.log.textContent += `[${time}] ${message}\n`;
  elements.log.scrollTop = elements.log.scrollHeight;
}

function setStatus(element, message, type = "") {
  element.textContent = message;
  element.className = `status ${type}`.trim();
}

function setBusy(value) {
  busy = value;
  window.clearTimeout(busyIndicatorTimer);
  if (value) {
    busyIndicatorTimer = window.setTimeout(() => {
      if (busy) {
        elements.sync_indicator.hidden = false;
        elements.sync.classList.add("syncing");
      }
    }, 400);
  } else {
    elements.sync_indicator.hidden = true;
    elements.sync.classList.remove("syncing");
  }
  elements.sync.disabled = value || !accessToken;
  elements.create.disabled = value || !accessToken || !hasNewNoteContent();
  elements.save.disabled = value;
  elements.delete.disabled = value;
}

function hasNewNoteContent() {
  return Boolean(elements.new_body.value.trim());
}

function clearAccessToken(message = "Google Drive未接続") {
  accessToken = undefined;
  accessTokenExpiresAt = 0;
  window.clearTimeout(accessTokenExpiryTimer);
  elements.auth_status.textContent = message;
  elements.connection.classList.remove("connected");
  elements.revoke.disabled = true;
  elements.sync.disabled = true;
  elements.open_search.disabled = true;
  elements.create.disabled = true;
  elements.save.disabled = true;
  elements.delete.disabled = true;
}

function expireAccessToken() {
  if (!accessToken) return;
  clearAccessToken("Google Drive再接続が必要");
  setStatus(elements.list_status, "認証の有効期限が切れました。再接続してください。", "error");
  log("認証の有効期限が切れました。表示中のメモは保持しています。");
}

function rememberPendingAction(action, statusElement) {
  pendingAction = action;
  expireAccessToken();
  setStatus(statusElement, "再接続すると、この操作を続行します。", "error");
}

async function driveFetch(url, options = {}) {
  if (!accessToken || Date.now() >= accessTokenExpiresAt) {
    expireAccessToken();
    throw new AuthorizationExpiredError("Google Driveへ再接続してください。");
  }
  const response = await fetch(url, {
    ...options,
    headers: { Authorization: `Bearer ${accessToken}`, ...(options.headers || {}) },
  });
  const body = await response.text();
  if (!response.ok) {
    if (response.status === 401) {
      expireAccessToken();
      throw new AuthorizationExpiredError("Google Driveへ再接続してください。");
    }
    throw new Error(`Drive API HTTP ${response.status}: ${body.slice(0, 300)}`);
  }
  return body;
}

function authorize() {
  const clientId = elements.client_id.value.trim();
  if (!clientId.endsWith(".apps.googleusercontent.com")) {
    elements.settings.showModal();
    setStatus(elements.list_status, "接続設定にWeb Client IDを入力してください。", "error");
    return;
  }
  if (!window.google?.accounts?.oauth2) {
    setStatus(elements.list_status, "Google認証ライブラリを読込中です。少し待って再実行してください。", "error");
    return;
  }
  localStorage.setItem("solimemo.webClientId", clientId);
  tokenClient = google.accounts.oauth2.initTokenClient({
    client_id: clientId,
    scope: DRIVE_SCOPE,
    callback: async (response) => {
      if (response.error) {
        setStatus(elements.list_status, `認証失敗: ${response.error_description || response.error}`, "error");
        return;
      }
      accessToken = response.access_token;
      const expiresInSeconds = Number(response.expires_in) || 3600;
      accessTokenExpiresAt = Date.now() + expiresInSeconds * 1000;
      window.clearTimeout(accessTokenExpiryTimer);
      accessTokenExpiryTimer = window.setTimeout(
        expireAccessToken,
        Math.max(0, expiresInSeconds * 1000 - 30_000),
      );
      localStorage.setItem("solimemo.hasAuthorized", "true");
      elements.auth_status.textContent = "Google Drive接続済み";
      elements.connection.classList.add("connected");
      elements.revoke.disabled = false;
      elements.search.disabled = false;
      elements.open_search.disabled = false;
      log("drive.file権限で認証しました。トークンはメモリにのみ保持します。");
      const action = pendingAction;
      pendingAction = undefined;
      if (action) await action();
      else await refreshNotes();
    },
    error_callback: (error) => setStatus(elements.list_status, `認証画面エラー: ${error.type}`, "error"),
  });
  const prompt = localStorage.getItem("solimemo.hasAuthorized") === "true"
    ? ""
    : "consent select_account";
  tokenClient.requestAccessToken({ prompt });
}

function revoke() {
  if (!accessToken) return;
  google.accounts.oauth2.revoke(accessToken, () => {
    clearAccessToken();
    pendingAction = undefined;
    localStorage.removeItem("solimemo.hasAuthorized");
    notes = [];
    renderTimeline();
    elements.search.disabled = true;
    elements.open_search.disabled = true;
    closeSearch();
    setStatus(elements.list_status, "Google Driveとの接続を解除しました。");
    log("接続を解除しました。");
  });
}

function parseMarkdown(content) {
  const normalized = content.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
  const lines = normalized.split("\n");
  if (lines[0] !== "---") throw new Error("front matterがありません");
  const closing = lines.indexOf("---", 1);
  if (closing < 0) throw new Error("front matterが閉じられていません");
  const values = {};
  for (const line of lines.slice(1, closing)) {
    const separator = line.indexOf(":");
    if (separator <= 0) throw new Error("front matterが不正です");
    values[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  }
  if (Number(values.schemaVersion) !== 1) throw new Error("未対応のschemaVersionです");
  for (const key of ["id", "title", "createdAt", "updatedAt", "deletedAt"]) {
    if (values[key] === undefined) throw new Error(`${key}がありません`);
  }
  const title = values.title === "null"
    ? null
    : values.title.startsWith('"') ? JSON.parse(values.title) : values.title;
  const bodyStart = lines[closing + 1] === "" ? closing + 2 : closing + 1;
  const createdAt = Date.parse(values.createdAt);
  const updatedAt = Date.parse(values.updatedAt);
  const deletedAt = values.deletedAt === "null" ? null : Date.parse(values.deletedAt);
  if ([createdAt, updatedAt, deletedAt].some((value) => value !== null && Number.isNaN(value))) {
    throw new Error("日時形式が不正です");
  }
  return {
    id: values.id,
    title,
    body: lines.slice(bodyStart).join("\n").replace(/\n+$/, ""),
    createdAt,
    updatedAt,
    deletedAt,
  };
}

function serializeMarkdown(note) {
  const body = note.body.replaceAll("\r\n", "\n").replaceAll("\r", "\n").replace(/\n+$/, "");
  return [
    "---",
    "schemaVersion: 1",
    `id: ${note.id}`,
    `title: ${note.title ? JSON.stringify(note.title) : "null"}`,
    `createdAt: ${new Date(note.createdAt).toISOString()}`,
    `updatedAt: ${new Date(note.updatedAt).toISOString()}`,
    `deletedAt: ${note.deletedAt === null ? "null" : new Date(note.deletedAt).toISOString()}`,
    "---",
    "",
    body,
    "",
  ].join("\n");
}

async function listMarkdownFiles() {
  const params = new URLSearchParams({
    q: `mimeType = '${MARKDOWN_MIME}' and trashed = false`,
    spaces: "drive",
    orderBy: "modifiedTime desc",
    fields: "files(id,name,version,modifiedTime)",
    pageSize: "1000",
  });
  return JSON.parse(await driveFetch(`${DRIVE_FILES}?${params}`)).files || [];
}

async function refreshNotes() {
  setBusy(true);
  setStatus(elements.list_status, "Google Driveからメモを読み込んでいます…");
  const loaded = [];
  let errors = 0;
  try {
    const files = await listMarkdownFiles();
    for (const metadata of files) {
      try {
        const content = await driveFetch(`${DRIVE_FILES}/${metadata.id}?alt=media`);
        loaded.push({ metadata, note: parseMarkdown(content) });
      } catch (error) {
        if (error instanceof AuthorizationExpiredError) throw error;
        errors += 1;
        log(`${metadata.name}を読めません: ${error.message}`);
      }
    }
    notes = loaded;
    renderTimeline();
    const activeCount = notes.filter((item) => item.note.deletedAt === null).length;
    setStatus(elements.list_status, `${activeCount}件のメモ${errors ? `（読込エラー ${errors}件）` : ""}`,
      errors ? "error" : "success");
    log(`${files.length}ファイルを確認しました。`);
  } catch (error) {
    if (error instanceof AuthorizationExpiredError) {
      rememberPendingAction(refreshNotes, elements.list_status);
      return;
    }
    setStatus(elements.list_status, `読込失敗: ${error.message}`, "error");
    log(`一覧取得失敗: ${error.message}`);
  } finally {
    setBusy(false);
  }
}

function renderTimeline() {
  const query = elements.search.value.trim().toLocaleLowerCase("ja-JP");
  const visible = notes
    .filter(({ note }) => note.deletedAt === null)
    .filter(({ note }) => !query || `${note.title || ""}\n${note.body}`.toLocaleLowerCase("ja-JP").includes(query))
    .sort((a, b) => a.note.updatedAt - b.note.updatedAt);
  elements.timeline.replaceChildren();
  if (visible.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = query ? "一致するメモはありません。" : "まだメモがありません。";
    elements.timeline.append(empty);
    return;
  }
  for (const item of visible) {
    const article = document.createElement("article");
    article.className = "note";
    article.tabIndex = 0;
    const body = document.createElement("p");
    const combined = legacyCompatibleBody(item.note);
    body.textContent = combined.length > 300 ? `${combined.slice(0, 300)}…` : combined;
    const time = document.createElement("time");
    time.dateTime = new Date(item.note.updatedAt).toISOString();
    time.textContent = new Date(item.note.updatedAt).toLocaleString("ja-JP");
    article.append(body, time);
    article.addEventListener("click", () => openEditor(item));
    article.addEventListener("keydown", (event) => { if (event.key === "Enter") openEditor(item); });
    elements.timeline.append(article);
  }
  if (!query) elements.timeline.scrollTop = elements.timeline.scrollHeight;
}

function legacyCompatibleBody(note) {
  if (!note.title) return note.body;
  return note.body ? `${note.title}\n\n${note.body}` : note.title;
}

async function findOrCreateFolder(name, parentId) {
  const escaped = name.replaceAll("'", "\\'");
  const params = new URLSearchParams({
    q: `name = '${escaped}' and mimeType = '${FOLDER_MIME}' and '${parentId}' in parents and trashed = false`,
    spaces: "drive",
    fields: "files(id)",
  });
  const found = JSON.parse(await driveFetch(`${DRIVE_FILES}?${params}`)).files || [];
  if (found.length) return found[0].id;
  return JSON.parse(await driveFetch(`${DRIVE_FILES}?fields=id`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name, mimeType: FOLDER_MIME, parents: [parentId] }),
  })).id;
}

async function createDriveFile(note) {
  const rootId = await findOrCreateFolder("SoliMemo", "root");
  const notesId = await findOrCreateFolder("notes", rootId);
  const boundary = `solimemo-${crypto.randomUUID()}`;
  const metadata = { name: `${note.id}.md`, mimeType: MARKDOWN_MIME, parents: [notesId] };
  const body = [
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n`,
    `${JSON.stringify(metadata)}\r\n`,
    `--${boundary}\r\nContent-Type: ${MARKDOWN_MIME}; charset=UTF-8\r\n\r\n`,
    `${serializeMarkdown(note)}\r\n--${boundary}--\r\n`,
  ].join("");
  return JSON.parse(await driveFetch(`${DRIVE_UPLOAD}?uploadType=multipart&fields=id,name,version,modifiedTime`, {
    method: "POST",
    headers: { "Content-Type": `multipart/related; boundary=${boundary}` },
    body,
  }));
}

async function updateDriveFile(item, note) {
  const current = JSON.parse(await driveFetch(
    `${DRIVE_FILES}/${item.metadata.id}?fields=id,name,version,modifiedTime`,
  ));
  if (String(current.version) !== String(item.metadata.version)) {
    throw new Error("別の端末で更新されています。再読込してから編集してください。");
  }
  return JSON.parse(await driveFetch(
    `${DRIVE_UPLOAD}/${item.metadata.id}?uploadType=media&fields=id,name,version,modifiedTime`,
    {
      method: "PATCH",
      headers: { "Content-Type": `${MARKDOWN_MIME}; charset=UTF-8` },
      body: serializeMarkdown(note),
    },
  ));
}

async function createNote() {
  const body = elements.new_body.value.trim();
  if (!body) return;
  setBusy(true);
  setStatus(elements.create_status, "Driveへ保存しています…");
  try {
    const now = Date.now();
    const note = { id: crypto.randomUUID(), title: null, body, createdAt: now, updatedAt: now, deletedAt: null };
    const metadata = await createDriveFile(note);
    notes.push({ metadata, note });
    elements.new_body.value = "";
    resizeComposer();
    renderTimeline();
    setStatus(elements.create_status, "投稿しました。", "success");
    log(`新規作成: ${metadata.name}`);
  } catch (error) {
    if (error instanceof AuthorizationExpiredError) {
      rememberPendingAction(createNote, elements.create_status);
      return;
    }
    setStatus(elements.create_status, `投稿失敗: ${error.message}`, "error");
  } finally {
    setBusy(false);
  }
}

function openEditor(item) {
  selected = item;
  elements.edit_body.value = legacyCompatibleBody(item.note);
  setStatus(elements.edit_status, "");
  elements.editor.showModal();
}

async function saveSelected(deleted = false, deletionConfirmed = false) {
  if (!selected) return;
  if (deleted && !deletionConfirmed && !window.confirm("このメモを削除しますか？")) return;
  const body = elements.edit_body.value;
  if (!deleted && !body.trim()) {
    setStatus(elements.edit_status, "本文を入力してください。", "error");
    return;
  }
  setBusy(true);
  setStatus(elements.edit_status, deleted ? "削除を同期しています…" : "保存しています…");
  try {
    const note = { ...selected.note, title: null, body, updatedAt: Date.now(), deletedAt: deleted ? Date.now() : null };
    const metadata = await updateDriveFile(selected, note);
    selected.note = note;
    selected.metadata = metadata;
    renderTimeline();
    elements.editor.close();
    log(`${deleted ? "論理削除" : "更新"}: ${note.id}`);
  } catch (error) {
    if (error instanceof AuthorizationExpiredError) {
      rememberPendingAction(() => saveSelected(deleted, deletionConfirmed || deleted), elements.edit_status);
      return;
    }
    setStatus(elements.edit_status, `保存失敗: ${error.message}`, "error");
  } finally {
    setBusy(false);
  }
}

elements.authorize.addEventListener("click", authorize);
elements.revoke.addEventListener("click", revoke);
elements.sync.addEventListener("click", refreshNotes);
elements.open_settings.addEventListener("click", () => elements.settings.showModal());
function updateCreateButton() {
  elements.create.disabled = busy || !accessToken || !hasNewNoteContent();
}
function resizeComposer() {
  elements.new_body.style.height = "auto";
  elements.new_body.style.height = `${Math.min(elements.new_body.scrollHeight, 128)}px`;
}
elements.new_body.addEventListener("input", () => {
  updateCreateButton();
  resizeComposer();
});
elements.new_body.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter") createNote();
});
elements.create.addEventListener("click", createNote);
elements.search.addEventListener("input", renderTimeline);
function openSearch() {
  document.body.classList.add("searching");
  elements.app_title.hidden = true;
  elements.header_search.hidden = false;
  elements.open_search.hidden = true;
  elements.sync.hidden = true;
  elements.open_settings.hidden = true;
  elements.search.focus();
}
function closeSearch() {
  document.body.classList.remove("searching");
  elements.search.value = "";
  elements.app_title.hidden = false;
  elements.header_search.hidden = true;
  elements.open_search.hidden = false;
  elements.sync.hidden = false;
  elements.open_settings.hidden = false;
  renderTimeline();
}
elements.open_search.addEventListener("click", openSearch);
elements.close_search.addEventListener("click", closeSearch);
elements.clear_search.addEventListener("click", () => {
  elements.search.value = "";
  elements.search.focus();
  renderTimeline();
});
elements.save.addEventListener("click", () => saveSelected(false));
elements.delete.addEventListener("click", () => saveSelected(true));
elements.editor.addEventListener("close", () => { selected = null; });

renderTimeline();
