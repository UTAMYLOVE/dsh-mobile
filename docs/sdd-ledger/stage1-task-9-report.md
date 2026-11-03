# Stage 1, Task 9: image and file attachments — report

Branch `lane-t9`, based on 093b21c (Task 8 plus Tasks 10-14).

## What was built
- `chat/Attachments.kt` (new): `PendingAttachment` (id, uri, name, mime, size; no bytes; `isImage` = accepted image type and <= 5 MB), `LoadResult`, `AttachmentLoader` (`inspect` counts the real stream and stops at 20 MB + 1 byte, never trusting `describe`; `read` runs at send time only and throws `AttachmentTooLarge` / `AttachmentUnreadable`), `AttachmentSender.toParts` (text first if not blank, then attachments in pick order; read and upload everything, first failure throws `AttachmentFailure` and nothing is sent), `SendOutcome`, `sendWithAttachments`, `decodeThumbnail` (bounds first, then `inSampleSize` decode, never a full bitmap).
- Rule: image type and <= 5 MB goes inline as `PromptPart.Image` (base64, name kept). Anything else, or an image that grew past 5 MB before sending, goes through `fileUploads/upload` as `PromptPart.File`. File limit is 20 MB, also enforced again at send ("a file that grew").
- `ui/chat/ChatPage.kt`: `sendFromDock(session, nav, text, attachments = emptyList(), loader)` now always sends via `sendWithAttachments`. `DockSend` is now `Sent | NotCreated(code) | AttachmentFailed(name, reason) | Refused(code)` (`NotSent` is replaced by `Refused`; its only user was MainActivity).
- `ui/shell/Dock.kt`: `AttachmentChip(id, name, isImage, thumbnail: (suspend () -> ImageBitmap?)? = null)` (the constructor stays compatible). Tiles are 64 dp, corner 16, in a horizontally scrolling row above the input row (cannot cover any button at 412 dp). The cross is a 24 dp circle, #5E4445 at alpha .70, white X, top-right, with `minimumInteractiveComponentSize()` (48 dp touch). Files show their name on a tile. The error uses `colorScheme.error` (#B3261E) with tag `COMPOSER_ERROR`. STOP now replaces send only when the input is empty and there are no tiles.
- `ui/MainActivity.kt`: `PickMultipleVisualMedia(9)` (ImageOnly) and `OpenMultipleDocuments` registered as fields (no permission). Attachments live in `mutableStateOf<List<PendingAttachment>>` (uri and name only). `addPicked` counts sizes on IO; an empty list (cancelled picker) changes nothing. `internal fun attachForTest(uris)`. Name and size come from `OpenableColumns`, mime from `getType` (extension fallback). Thumbnails decode on IO, 64 dp worth of pixels.
- `onDockSend`: text only behaves exactly as Task 8 (field emptied at once, restored on failure, ready-gating, switch to 對話, create-then-send). With attachments, text and tiles stay until dsh accepts (cleared only on Sent, and only what was sent), send is disabled meanwhile (`sending`), and any failure leaves both in place with the error shown. `canSend` = (text not blank or attachments) and not sending.
- `Tags.COMPOSER_ERROR` added (ATTACHMENT_CHIP / ATTACHMENT_REMOVE already existed). Strings added: attach_too_large, attach_unreadable, attach_upload_failed, attach_refused_with, attach_refused (texts verbatim from the brief).

## Protocol findings (read-only against the installed dsh packages)
- `fileUploads/upload`: `dsh-client-file-upload/lib/typert.remote-client.js` and `.d.ts` declare `TypertRemoteMethod<{ agentId: SessionId; request: EncodedFileUploadRequest }, FileUploadValue>`, and the host codec lists wire names `agentId` (scope wire) and `request` (strict object, `data` required base64, `name` optional, no other keys). The result is `{receiptId, file:{attachmentId,name,bytes}}`. So unary calls are named-argument objects (`payload.args = {agentId, request}`), the same as every other DshRemote call; `DshRemote.upload` already sends exactly that and reads `receiptId`. No change needed to `DshRemote.upload` or its test.
- `session/prompt` content (`dsh-api-session-controller/lib/types/types.d.ts` `PromptContentPart`): `{type:'text',text}` | `{type:'image', mediaType: png|jpeg|webp|gif, data: base64, name?}` | `{type:'file', receiptId}`. `PromptPart` and `DshRemote.contentPart` in the app match; no change.
- Note: dsh admits images through its own limits (`ImageAttachmentLimits`: size, count per message, pixels, dimension) and normalizes them; the app's 5 MB inline limit is the planner's rule, and a dsh refusal surfaces as `Refused(code)`.

## Tests (RED then GREEN)
- RED: with the tests written first, `:app:testDebugUnitTest --tests AttachmentsTest` failed to compile (Unresolved reference AttachmentLoader, LoadResult, AttachmentSender, ...).
- GREEN: `AttachmentsTest` (10), `DockAttachmentsTest` (3, at w412dp), `MainActivityTest` +4 (`aCancelledPickerChangesNothing`, `anUploadFailureKeepsTextAndChipsAndSendsNoPrompt`, `aRefusedAttachmentShowsTheCodeInTheComposer`, `sendIsEnabledWithOnlyAnAttachmentInTheApp`). Full unit suite: 969 tests, 0 failures, 0 skipped. `assembleDebug` and `assembleDebugAndroidTest` succeed.
- The Task 8 tests (ChatPage, MainActivity chat tests, FogShell overlap tests with chips) pass unchanged.

## Deviations
- `AttachmentsTest` and the test pick helper use `file://` URIs in `MainActivityTest` (Robolectric has no content provider); name falls back to the last path segment and mime to the extension.
- Brief text "超過 %2$d MB，沒有附上" is also used for a file that grew past the limit at send time (the tiles stay; nothing was sent).
- Brief used `PickVisualMedia`; the multi-select variant `PickMultipleVisualMedia` (same contract family, no permission) is used so several images can be picked.
- A refusal of a text-only message now also sets the dock error (「dsh 沒有收下這則訊息：%s」, per the brief), in addition to the chat page's existing send error.
- No 412 dp screenshots were rendered (optional).

## Concerns
- `inspect` reads each picked file once (up to 20 MB) to count it, and `read` reads it again at send; this is by design (bytes are not kept) but costs IO for large files. Counting is on IO and sequential.
- `attachments` and `sending` are Activity fields (like `composerText`): a recreated Activity loses them; the URI grant of a picker result is not persisted.
- Red lines: no new permissions, no names, URIs or contents logged (error classes and dsh safe codes only), nothing uploaded except to the local dsh.
