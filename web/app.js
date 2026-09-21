const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
const DRIVE_FILES = "https://www.googleapis.com/drive/v3/files";
const DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
const FOLDER_MIME = "application/vnd.google-apps.folder";
const MARKDOWN_MIME = "text/markdown";
const DRIVE_DOWNLOAD_CONCURRENCY = 6;

const elements = Object.fromEntries([
  "client-id", "client-id-config", "authorize", "revoke", "auth-status", "connection", "sync",
  "sync-indicator", "open-search", "open-tags", "close-search", "clear-search", "header-search", "app-title",
  "open-settings", "settings", "log", "new-body", "create", "create-status", "search",
  "list-status", "timeline", "editor", "editor-title", "editor-view", "editor-input", "edit-body",
  "editor-tags", "edit-tags-list", "edit-tag-input", "add-tag", "tag-suggestions", "edit-status", "start-edit", "save", "delete", "toggle-pin",
  "open-trash", "trash-dialog", "close-trash", "close-trash-bottom", "trash-list", "empty-trash",
  "tags-dialog", "close-tags", "close-tags-bottom", "tag-filter-list", "clear-tag-filter",
].map((id) => [id.replaceAll("-", "_"), document.querySelector(`#${id}`)]));

let tokenClient;
let accessToken;
let accessTokenExpiresAt = 0;
let accessTokenExpiryTimer;
let pendingAction;
let notes = [];
let selected = null;
let selectedTag = null;
let editingTags = [];
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
  elements.toggle_pin.disabled = value;
  elements.open_trash.disabled = value || !accessToken;
  elements.open_tags.disabled = value || !accessToken;
  elements.empty_trash.disabled = value || !accessToken || !notes.some(({ note }) => note.deletedAt !== null);
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
  elements.open_trash.disabled = true;
  elements.open_tags.disabled = true;
  elements.empty_trash.disabled = true;
  selectedTag = null;
  elements.open_tags.textContent = "#";
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
      elements.open_trash.disabled = false;
      elements.open_tags.disabled = false;
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
  const metadataUpdatedAt = values.metadataUpdatedAt === undefined
    ? updatedAt
    : Date.parse(values.metadataUpdatedAt);
  const deletedAt = values.deletedAt === "null" ? null : Date.parse(values.deletedAt);
  if ([createdAt, updatedAt, metadataUpdatedAt, deletedAt].some((value) => value !== null && Number.isNaN(value))) {
    throw new Error("日時形式が不正です");
  }
  return {
    id: values.id,
    title,
    body: lines.slice(bodyStart).join("\n").replace(/\n+$/, ""),
    createdAt,
    updatedAt,
    deletedAt,
    pinned: values.pinned === "true",
    tags: values.tags === undefined ? [] : normalizeTags(JSON.parse(values.tags)),
    metadataUpdatedAt,
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
    `metadataUpdatedAt: ${new Date(note.metadataUpdatedAt ?? note.updatedAt).toISOString()}`,
    `deletedAt: ${note.deletedAt === null ? "null" : new Date(note.deletedAt).toISOString()}`,
    `pinned: ${Boolean(note.pinned)}`,
    `tags: ${JSON.stringify(normalizeTags(note.tags || []))}`,
    "---",
    "",
    body,
    "",
  ].join("\n");
}

function normalizeTags(values) {
  return [...new Set(values.map((value) => String(value).trim())
    .filter((value) => value && value.length <= 30 && !/[\r\n]/.test(value)))].slice(0, 10);
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
    const cachedById = new Map(notes.map((item) => [item.metadata.id, item]));
    const results = new Array(files.length);
    let nextIndex = 0;

    async function loadNext() {
      while (nextIndex < files.length) {
        const index = nextIndex++;
        const metadata = files[index];
        const cached = cachedById.get(metadata.id);
        if (cached && String(cached.metadata.version) === String(metadata.version)) {
          results[index] = { metadata, note: cached.note };
          continue;
        }
        try {
          const content = await driveFetch(`${DRIVE_FILES}/${metadata.id}?alt=media`);
          results[index] = { metadata, note: parseMarkdown(content) };
        } catch (error) {
          if (error instanceof AuthorizationExpiredError) throw error;
          errors += 1;
          log(`${metadata.name}を読めません: ${error.message}`);
        }
      }
    }

    const workerCount = Math.min(DRIVE_DOWNLOAD_CONCURRENCY, files.length);
    await Promise.all(Array.from({ length: workerCount }, loadNext));
    loaded.push(...results.filter(Boolean));
    notes = loaded;
    renderTimeline();
    const activeCount = notes.filter((item) => item.note.deletedAt === null).length;
    setStatus(elements.list_status, `${activeCount}件のメモ${errors ? `（読込エラー ${errors}件）` : ""}`,
      errors ? "error" : "success");
    const downloadedCount = files.filter((metadata) => {
      const cached = cachedById.get(metadata.id);
      return !cached || String(cached.metadata.version) !== String(metadata.version);
    }).length;
    log(`${files.length}ファイルを確認し、${downloadedCount}ファイルを読み込みました。`);
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
  elements.open_tags.textContent = selectedTag ? "#✓" : "#";
  elements.open_tags.title = selectedTag ? `タグ: ${selectedTag}` : "タグで絞り込み";
  const query = elements.search.value.trim().toLocaleLowerCase("ja-JP");
  const visible = notes
    .filter(({ note }) => note.deletedAt === null)
    .filter(({ note }) => !selectedTag || (note.tags || []).includes(selectedTag))
    .filter(({ note }) => !query || `${note.title || ""}\n${note.body}\n${(note.tags || []).join(" ")}`.toLocaleLowerCase("ja-JP").includes(query))
    .sort((a, b) => Number(Boolean(a.note.pinned)) - Number(Boolean(b.note.pinned)) || a.note.updatedAt - b.note.updatedAt);
  elements.timeline.replaceChildren();
  if (visible.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = query ? "一致するメモはありません。" : "まだメモがありません。";
    elements.timeline.append(empty);
    return;
  }
  for (const [index, item] of visible.entries()) {
    if (item.note.pinned && (index === 0 || !visible[index - 1].note.pinned)) {
      const divider = document.createElement("div");
      divider.className = "pinned-divider";
      divider.textContent = "📌 ピン留め";
      elements.timeline.append(divider);
    }
    const article = document.createElement("article");
    article.className = "note";
    article.tabIndex = 0;
    const combined = legacyCompatibleBody(item.note);
    const urls = extractUrls(combined);
    const preview = removeUrls(combined);
    const body = document.createElement("p");
    body.textContent = preview.length > 300 ? `${preview.slice(0, 300)}…` : preview;
    if (!preview) body.hidden = true;
    const urlRow = document.createElement("div");
    urlRow.className = "note-url-row";
    if (urls.length) {
      const link = document.createElement("a");
      link.className = "note-url";
      link.href = urls[0];
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = urls[0];
      link.addEventListener("click", (event) => event.stopPropagation());
      urlRow.append(link);
      if (urls.length > 1) {
        const more = document.createElement("span");
        more.textContent = `ほか${urls.length - 1}件`;
        urlRow.append(more);
      }
    } else {
      urlRow.hidden = true;
    }
    const tags = renderTagChips(item.note.tags || [], 3);
    const time = document.createElement("time");
    time.dateTime = new Date(item.note.updatedAt).toISOString();
    time.textContent = new Date(item.note.updatedAt).toLocaleString("ja-JP");
    article.append(body, urlRow, tags, time);
    article.addEventListener("click", () => openEditor(item));
    article.addEventListener("keydown", (event) => { if (event.key === "Enter") openEditor(item); });
    elements.timeline.append(article);
  }
  if (!query) elements.timeline.scrollTop = elements.timeline.scrollHeight;
}

function renderTagChips(tags, limit = tags.length) {
  const container = document.createElement("div");
  container.className = "tag-list";
  normalizeTags(tags).slice(0, limit).forEach((tag) => {
    const chip = document.createElement("span");
    chip.className = "tag-chip";
    chip.textContent = `#${tag}`;
    container.append(chip);
  });
  if (tags.length > limit) {
    const more = document.createElement("span");
    more.className = "tag-more";
    more.textContent = `ほか${tags.length - limit}件`;
    container.append(more);
  }
  if (!container.childElementCount) container.hidden = true;
  return container;
}

function allTags() {
  return [...new Set(notes.filter(({ note }) => note.deletedAt === null)
    .flatMap(({ note }) => normalizeTags(note.tags || [])))].sort((a, b) => a.localeCompare(b, "ja"));
}

function appendLinkifiedText(container, text) {
  const pattern = /https?:\/\/[^\s]+/gi;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    container.append(document.createTextNode(text.slice(cursor, match.index)));
    const url = match[0].replace(/[.,。、)）\]】]+$/, "");
    const link = document.createElement("a");
    link.href = url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = url;
    link.addEventListener("click", (event) => event.stopPropagation());
    container.append(link, document.createTextNode(match[0].slice(url.length)));
    cursor = match.index + match[0].length;
  }
  container.append(document.createTextNode(text.slice(cursor)));
}

function normalizeUrl(value) {
  return value.replace(/[.,。、)）\]】]+$/, "");
}

function extractUrls(text) {
  return [...new Set([...text.matchAll(/https?:\/\/[^\s]+/gi)]
    .map((match) => normalizeUrl(match[0]))
    .filter(Boolean))];
}

function removeUrls(text) {
  return text.replace(/https?:\/\/[^\s]+/gi, (value) => value.slice(normalizeUrl(value).length))
    .trim()
    .replace(/\n{3,}/g, "\n\n");
}

function renderTrash() {
  const deleted = notes
    .filter(({ note }) => note.deletedAt !== null)
    .sort((a, b) => b.note.deletedAt - a.note.deletedAt);
  elements.trash_list.replaceChildren();
  elements.empty_trash.disabled = deleted.length === 0 || busy;
  if (deleted.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "ゴミ箱は空です。";
    elements.trash_list.append(empty);
    return;
  }
  for (const item of deleted) {
    const card = document.createElement("article");
    card.className = "trash-note";
    const body = document.createElement("p");
    appendLinkifiedText(body, legacyCompatibleBody(item.note));
    const time = document.createElement("time");
    time.dateTime = new Date(item.note.updatedAt).toISOString();
    time.textContent = new Date(item.note.updatedAt).toLocaleString("ja-JP");
    const actions = document.createElement("div");
    actions.className = "dialog-actions";
    const restore = document.createElement("button");
    restore.type = "button";
    restore.className = "secondary";
    restore.textContent = "復元";
    restore.addEventListener("click", () => restoreNote(item));
    const purge = document.createElement("button");
    purge.type = "button";
    purge.className = "danger";
    purge.textContent = "完全に削除";
    purge.addEventListener("click", () => purgeNote(item));
    actions.append(restore, purge);
    card.append(body, time, actions);
    elements.trash_list.append(card);
  }
}

async function restoreNote(item) {
  setBusy(true);
  try {
    const note = { ...item.note, deletedAt: null };
    item.metadata = await updateDriveFile(item, note);
    item.note = note;
    renderTrash();
    renderTimeline();
  } catch (error) {
    window.alert(`復元に失敗しました: ${error.message}`);
  } finally {
    setBusy(false);
  }
}

async function purgeNote(item, confirmed = false) {
  if (!confirmed && !window.confirm("このメモを完全に削除しますか？ この操作は取り消せません。")) return;
  setBusy(true);
  try {
    await driveFetch(`${DRIVE_FILES}/${item.metadata.id}`, { method: "DELETE" });
    notes = notes.filter((candidate) => candidate !== item);
    renderTrash();
  } catch (error) {
    window.alert(`完全削除に失敗しました: ${error.message}`);
  } finally {
    setBusy(false);
  }
}

async function emptyTrash() {
  const deleted = notes.filter(({ note }) => note.deletedAt !== null);
  if (!deleted.length || !window.confirm("ゴミ箱内のすべてのメモを完全に削除しますか？")) return;
  for (const item of deleted) await purgeNote(item, true);
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
    const note = {
      id: crypto.randomUUID(), title: null, body, createdAt: now, updatedAt: now, deletedAt: null,
      pinned: false, tags: [],
      metadataUpdatedAt: now,
    };
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
  const body = legacyCompatibleBody(item.note);
  elements.edit_body.value = body;
  elements.editor_view.replaceChildren();
  appendLinkifiedText(elements.editor_view, body);
  const editorTagChips = renderTagChips(item.note.tags || []);
  elements.editor_tags.replaceChildren(...editorTagChips.children);
  elements.editor_tags.hidden = !elements.editor_tags.childElementCount;
  editingTags = normalizeTags(item.note.tags || []);
  elements.edit_tag_input.value = "";
  renderEditableTags();
  elements.tag_suggestions.replaceChildren(...allTags().map((tag) => {
    const option = document.createElement("option");
    option.value = tag;
    return option;
  }));
  elements.toggle_pin.textContent = item.note.pinned ? "ピン留め解除" : "ピン留め";
  elements.editor_title.textContent = "メモ";
  elements.editor_view.hidden = false;
  elements.editor_input.hidden = true;
  elements.start_edit.hidden = false;
  elements.save.hidden = true;
  setStatus(elements.edit_status, "");
  elements.editor.showModal();
}

async function togglePinSelected() {
  if (!selected) return;
  setBusy(true);
  try {
    const note = { ...selected.note, pinned: !selected.note.pinned, metadataUpdatedAt: Date.now() };
    selected.metadata = await updateDriveFile(selected, note);
    selected.note = note;
    elements.toggle_pin.textContent = note.pinned ? "ピン留め解除" : "ピン留め";
    renderTimeline();
  } catch (error) {
    window.alert(`ピン留めの更新に失敗しました: ${error.message}`);
  } finally {
    setBusy(false);
  }
}

function startEditing() {
  elements.editor_title.textContent = "メモを編集";
  elements.editor_view.hidden = true;
  elements.editor_tags.hidden = true;
  elements.editor_input.hidden = false;
  elements.start_edit.hidden = true;
  elements.save.hidden = false;
  elements.edit_body.focus();
}

function renderEditableTags() {
  elements.edit_tags_list.replaceChildren();
  editingTags.forEach((tag) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "tag-chip editable";
    button.textContent = `#${tag} ×`;
    button.addEventListener("click", () => {
      editingTags = editingTags.filter((value) => value !== tag);
      renderEditableTags();
    });
    elements.edit_tags_list.append(button);
  });
  elements.add_tag.disabled = editingTags.length >= 10 || !elements.edit_tag_input.value.trim();
}

function addEditingTag() {
  const value = elements.edit_tag_input.value.trim();
  if (!value || value.length > 30 || /[\r\n]/.test(value) || editingTags.includes(value) || editingTags.length >= 10) return;
  editingTags.push(value);
  elements.edit_tag_input.value = "";
  renderEditableTags();
}

async function saveSelected(deleted = false, deletionConfirmed = false) {
  if (!selected) return;
  if (deleted && !deletionConfirmed && !window.confirm("このメモを削除しますか？")) return;
  const body = elements.edit_body.value;
  const tags = normalizeTags(editingTags);
  if (!deleted && !body.trim()) {
    setStatus(elements.edit_status, "本文を入力してください。", "error");
    return;
  }
  setBusy(true);
  setStatus(elements.edit_status, deleted ? "削除を同期しています…" : "保存しています…");
  try {
    const now = Date.now();
    const contentChanged = selected.note.title !== null || body !== selected.note.body;
    const metadataChanged =
      JSON.stringify(normalizeTags(selected.note.tags || [])) !== JSON.stringify(tags);
    const note = {
      ...selected.note,
      title: null,
      body,
      updatedAt: deleted || !contentChanged ? selected.note.updatedAt : now,
      deletedAt: deleted ? now : null,
      pinned: deleted ? false : selected.note.pinned,
      tags,
      metadataUpdatedAt: metadataChanged ? now : (selected.note.metadataUpdatedAt ?? selected.note.updatedAt),
    };
    const metadata = await updateDriveFile(selected, note);
    selected.note = note;
    selected.metadata = metadata;
    renderTimeline();
    renderTrash();
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
elements.open_trash.addEventListener("click", () => {
  elements.settings.close();
  renderTrash();
  elements.trash_dialog.showModal();
});
elements.close_trash.addEventListener("click", () => elements.trash_dialog.close());
elements.close_trash_bottom.addEventListener("click", () => elements.trash_dialog.close());
elements.empty_trash.addEventListener("click", emptyTrash);
function renderTagFilter() {
  elements.tag_filter_list.replaceChildren();
  const tags = allTags();
  if (!tags.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "タグがまだありません。";
    elements.tag_filter_list.append(empty);
  } else {
    tags.forEach((tag) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = tag === selectedTag ? "tag-filter active" : "tag-filter secondary";
      button.textContent = `#${tag}`;
      button.addEventListener("click", () => {
        selectedTag = tag;
        elements.tags_dialog.close();
        renderTimeline();
      });
      elements.tag_filter_list.append(button);
    });
  }
  elements.clear_tag_filter.disabled = selectedTag === null;
}
elements.open_tags.addEventListener("click", () => { renderTagFilter(); elements.tags_dialog.showModal(); });
elements.close_tags.addEventListener("click", () => elements.tags_dialog.close());
elements.close_tags_bottom.addEventListener("click", () => elements.tags_dialog.close());
elements.clear_tag_filter.addEventListener("click", () => {
  selectedTag = null;
  elements.tags_dialog.close();
  renderTimeline();
});
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
  elements.open_tags.hidden = true;
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
  elements.open_tags.hidden = false;
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
elements.start_edit.addEventListener("click", startEditing);
elements.toggle_pin.addEventListener("click", togglePinSelected);
elements.edit_tag_input.addEventListener("input", renderEditableTags);
elements.edit_tag_input.addEventListener("keydown", (event) => {
  if (event.key === "Enter") { event.preventDefault(); addEditingTag(); }
});
elements.add_tag.addEventListener("click", addEditingTag);
elements.delete.addEventListener("click", () => saveSelected(true));
elements.editor.addEventListener("close", () => { selected = null; });

renderTimeline();
