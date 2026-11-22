# Kai attribution (provider layer)

Kai 9000 (https://github.com/SimonSchubert/Kai, (c) Simon Schubert) is licensed under the Apache License 2.0.

Our review of Kai (coach/refs/G-kai-review.md, commit dc4cc9b) recommended borrowing two pieces of its provider layer:
the tool-call pairing repair of `OpenAIMessages.kt` / `AnthropicMessages.kt` and `InlineToolCallParser.kt`.

What this module actually contains:

- `ToolPairing.kt` and `InlineToolCallParser.kt` implement the same behaviour (answer every tool call exactly once, drop stray
  results; recover tool calls a model wrote into its text). They were written from scratch for this project: Kai's source was
  not present in this workspace when T04 was built, so **no Kai code was copied** and nothing here is a modified Kai file.
- The other lessons named in the review (send DeepSeek's `reasoning_content` back, retry only 429 and 5xx, normalise a
  `/chat/completions` base URL) are design ideas, also implemented independently.

Because no Kai source is included, the Apache-2.0 redistribution conditions (licence copy, "modified" marks) do not apply to
these files. We keep this note, and a credit line on the app's 授權 page (T09), as a courtesy and so that a later change that
does copy Kai code knows to add the licence text and mark the copied files as modified.
