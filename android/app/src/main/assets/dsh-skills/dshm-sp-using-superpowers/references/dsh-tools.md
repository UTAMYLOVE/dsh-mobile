# dsh Tool Mapping (DSH Mobile)

Skills speak in actions ("dispatch a subagent", "create a todo", "read a file"). In dsh, the official DeepSeek harness that
DSH Mobile runs on the owner's Android phone, these resolve to the tools below.

| Action skills request | dsh equivalent |
|----------------------|----------------|
| Read a file | `read` (images: `read_image`) |
| Create a new file | `write` |
| Edit a file | `edit` |
| Run a shell command | `bash` (each call is a fresh shell: pass `workdir` instead of `cd`) |
| Search file contents | `grep` |
| Find files by name | `glob` |
| Fetch a URL / search the web | `web_fetch` / `web_search` |
| Invoke a skill (`Skill` tool) | `skill` with the exact name, e.g. `dshm-sp-writing-plans` |
| Dispatch a subagent (`Agent` / `Task` tool) with a self-contained prompt | `subagent` (`description` + `prompt`) |
| Dispatch a subagent that should see this conversation | `subagent_fork` |
| Resume, steer or answer a subagent | `send_message` to its id; `list_agents`; `interrupt_agent` to stop one |
| Many independent subagents at once (fan-out) | several `subagent` calls in one step, or `workflow` when the owner asked for large-scale orchestration |
| Task tracking (`TodoWrite`, "create a todo", "mark complete") | `todo_write` (always the complete list) |
| A long objective that spans many rounds | `create_goal` / `get_goal` / `update_goal` |
| Ask the user a question (`AskUserQuestion`) | `ask_user_question` (short options, recommended one first) |
| Plan mode (`ExitPlanMode`) | `exit_plan_mode` to present the plan for approval |
| Hand over a finished file (a document, a spreadsheet, a page) | `present` |
| Remind later / recurring work | `schedule_create` (and `schedule_list`, `schedule_update`, `schedule_delete`) |
| Background jobs | `job_list`, `job_output`, `job_kill` |
| Remember something across conversations | the app's memory tools (`memory_write`, `memory_search`) |

## Instructions file

When a skill mentions "your instructions file" or `CLAUDE.md`, dsh reads **`AGENTS.md`** (and `CLAUDE.md`) in the
workspace. The owner's long-term preferences live in the app's memory (`USER.md`, `MEMORY.md` in the workspace), reached
with the memory tools.

## Subagents

`subagent` returns the child's id at once; the runtime tells you when it finishes, and the child reports back with
`send_message`. Wait for that notification instead of polling. A subagent does not share this conversation: give it
everything it needs in `prompt` (or write it to a file and give the path), as the skills ask.

dsh chooses the subagent's model from the app's settings (the owner picks a lead model and the app routes the rest). There
is no per-dispatch model choice, so skip the model-tier choices in "Model Selection" sections; everything else in them
(keep prompts small, hand artifacts over as files) still applies.

The owner can watch every subagent in the app's 代理人 war room. Give each one a short, meaningful `description`.

## Where things live

- The workspace (your working directory) is the owner's: the app shows its files, and the owner can share or export them.
  Plans, specs and ledgers the skills write belong under `docs/` or the scratch folder the skill names, as usual.
- git, bash, ripgrep and node are available. There is no browser on your side; a page you serve on 127.0.0.1 (for example
  the brainstorming visual companion) can be opened by the owner in the phone's own browser at that address.
- Commands that leave the workspace or change something outside it ask the owner for approval in the app. That is the
  harness's own approval flow: wait for the answer, never route around it.

## Skill invocation

The superpowers skills are installed as `dshm-sp-<name>`. `dshm-sp-using-superpowers` explains when to load the others;
load a skill with `skill` before acting on a task it covers, and follow its full text.
