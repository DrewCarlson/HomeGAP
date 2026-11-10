# Writing style guide

This page is the brief for everyone who writes HomeGAP documentation, people and agents alike.
The rules are a working profile of ASD-STE100 Simplified Technical English (STE), adapted for
software documentation, plus rules for accuracy and for a natural, human voice.

---

## Part 0: Role and scope

You write and edit the HomeGAP documentation in `docs/`. You get a brief for each page. The brief
lists the facts, the sources in the code, and the order of the page.

- Use only the facts in the brief, or facts you confirm in the source files that the brief names.
- Do not add a feature, option, default, number, command, UI label, file path or behavior that
  the brief or the source does not show.
- If a fact that you need is not in the brief, and you cannot confirm it in the source, do not
  guess. Put an HTML comment `<!-- FACT-CHECK: what is missing -->` at that location and list it
  in your report.
- Write only files under `docs/`. Do not change code, `mkdocs.yml` or other files, unless the brief
  tells you to.
- Use `example.net` for domains, `na1`, `eu1`, `as1` for node ids and documentation addresses
  (`203.0.113.0/24`, `198.51.100.0/24`) for public IP addresses. Never use a real domain, a real
  person's name or a real location of a user's homelab.

---

## Part 1: The nine grammatical and structural pillars

### Section 1: Words and parts of speech

- **Rule 1.1: One word, one meaning.** Use each word as one part of speech, with one meaning.
    - Use "close" only as a verb ("Close the dialog"). For the adjective, use "near".
    - Use "oil" only as a noun. Write "Apply oil to the bearing", not "Oil the bearing".
- **Rule 1.2: Use verbs for actions, not nouns made from verbs.**
    - Not: "Perform an inspection of the seal." / "Execute the alignment."
    - Write: "Inspect the seal." / "Align the component."
- **Rule 1.3: Do not use phrasal verbs.**
    - Not: "set up", "put together", "take apart", "carry out", "turn on", "find out".
    - Write: "prepare" or "configure", "assemble", "disassemble", "do", "enable" or "start",
      "learn" or "find".
    - Exception: a phrasal verb that is a name in a UI or a command (the **Add node** button,
      `docker compose up`) stays as it is.

### Section 2: Noun clusters

- **Rule 2.1: Not more than three nouns in a row.** Break longer clusters with prepositions.
    - Not: "node cluster member database container restart".
    - Write: "a restart of the database container on the node's cluster member".
    - Exception: a technical name stays as it is (`cluster-proxy`, "Pangolin EE license key").

### Section 3: Verbs and tenses

- **Rule 3.1: Use only three verb forms.**
    1. The infinitive, for a purpose: "To add a node, ...".
    2. The imperative, for a step: "Open", "Set", "Run".
    3. The present simple, for a description: "The orchestrator reads the labels."
- **Rule 3.2: No compound or future tenses.** Use the past participle only as an adjective
  before a noun ("the saved stack"). Do not write "will", "has been", "will have".
    - Not: "The node will be removed from DNS."
    - Write: "HomeGAP removes the node from DNS."
- **Rule 3.3: Do not use "-ing" forms for actions or states.**
    - Not: "While provisioning the node, ..." / "The tunnel is reconnecting."
    - Write: "During provisioning, ..." / "The tunnel reconnects."
    - Exception: a technical name or UI text with "-ing" stays as it is (`provisioning…`,
      "Pending switch-back", the `streaming` replica state, `ping`).

### Section 4: Sentences

- **Rule 4.1: Word limits.**
    - A procedural sentence (a step): 20 words or fewer.
    - A descriptive sentence: 25 words or fewer.
    - Code, labels, paths and URLs count as one word each.
- **Rule 4.2: One idea in each sentence.** A sentence can have one dependent clause, if that
  clause gives a simple condition ("If the tunnel is down, ...").
- **Rule 4.3: No hedging.** Do not use *should*, *would*, *may*, *might*, *likely*, *probably*,
  *could*, *perhaps*, *usually* (unless the code shows a real "usually", give the condition).
    - For a requirement, use **must**.
    - For a capability, use **can**.
    - For a recommendation, say it directly: "We recommend ...", or give the reason: "Use
      `tunnels.mtu: 1420`. The default of 1280 decreases throughput."

### Section 5: Procedures

- **Rule 5.1: One action in each step.** Put each action in its own numbered step. A step can
  have a second sentence that tells the result ("The dashboard shows the node as `provisioning…`.").
- **Rule 5.2: Condition before command.** Give the condition, the requirement or the
  precaution before the action.
    - Not: "Press **Run** after you test the connection."
    - Write: "Test the connection. Then click **Run**." or "When the test passes, click **Run**."

### Section 6: Descriptions

- **Rule 6.1: Not more than six sentences in a paragraph.** Do not put descriptive text and
  numbered steps in the same block. Put the description first, then the procedure.

### Section 7: Safety messages

- **Rule 7.1: Mark each risk clearly, and isolate it.** Start with the condition or the action,
  then give the risk.
    - Use `!!! danger "Warning"` when there is a risk of data loss, of lockout from a server, or of
      exposure of a private service or a secret.
    - Use `!!! warning "Caution"` when there is a risk of an interruption of service or of a failed
      operation.
    - Use `!!! note` for information that is not a risk, and `!!! tip` for a shortcut.
    - Example: "If you restrict SSH with `ssh_allow_from` and your public IP address changes,
      the orchestrator cannot connect to the node."

### Sections 8 and 9: Punctuation and completeness

- **Rule 8.1: Do not drop words.** Keep "the", "a" and "an". Keep verbs. No telegraphic text,
  also in table cells and list items that are sentences.
- **Rule 8.2: No semicolons.** Make two sentences.

---

## Part 2: Word list (one word, one meaning)

| Do not use | Use |
| --- | --- |
| begin, commence, initiate | **start** |
| utilize, employ, leverage | **use** |
| terminate, halt, conclude (as general verbs) | **stop** or **end** |
| perform, execute, carry out | the specific verb (**do**, **run**, **check**, **install**) |
| frequently, periodically | the exact interval ("every 60 seconds") or **often** |
| verify, ascertain, ensure | **make sure** or **check** |
| permit, allow (as general verbs) | **let**, or say what a person **can** do |
| set up | **configure** or **prepare** (and the noun **setup**) |
| take down | **stop** or **remove** |
| in order to | **to** |
| via | **through** or **with** |
| e.g., i.e. | "for example", "that is" |

**Technical names (TN) and technical verbs.** Keep words that are names of HomeGAP, Pangolin,
Traefik, Bunny, Docker or Linux things, also if STE does not approve them. Examples: "orchestrator",
"node", "front door", "passthrough", "terminate" (the TLS mode), "provision", "reconcile",
"deploy", "label", "router", "middleware", "allowlist", "replica", "primary", "switchover",
"failover", "connector", "tunnel", "blueprint". Write a name the same way every time.

**Literal text is outside these rules.** Code blocks, inline code, label names, config keys,
commands, log lines, error messages and UI labels stay exactly as the source shows them. Quote a UI
label in bold (**Add node**) and a value or key in code (`homegap.tls`).

---

## Part 3: Examples

### Example 1: No passive voice, no hedging

- **Input:** "It should be ensured that the interface cover is periodically checked by the
  maintenance crew to prevent any accumulating dust from causing a system malfunction."
- **STE:** "The maintenance crew must check the interface cover every week. Dust can cause a
  system failure."
- The passive "It should be ensured" and the hedge "should" are gone. The "-ing" form
  "accumulating" is gone. There are two short sentences, each with one idea.

### Example 2: No noun clusters, no phrasal verbs

- **Input:** "Before carrying out the automated backup system software configuration update,
  verify that the primary storage drive is completely empty."
- **STE:** "Check the primary storage drive. Make sure that it is empty. Then, update the
  configuration of the software for the automated backup system."
- The condition comes first. A preposition breaks the noun cluster. The phrasal verb "carrying
  out" is gone.

### Example 3: HomeGAP text

- **Input:** "Once you've set up the tunnels container, HomeGAP will automatically start routing
  your traffic through the nearest healthy node, which should give you seamless failover."
- **STE:** "When the `tunnels` container runs, Bunny DNS sends each viewer to the nearest healthy
  node. If a node fails its health check, Bunny removes it from the answers."

---

## Part 4: A human voice

STE limits the grammar. It does not make the text robotic. Write as an experienced engineer who
explains the system to a colleague.

- **Be concrete.** Give the real value, the real path and the real result. "The node returns a
  503 page about 15 seconds after it loses the homelab" is better than "the system degrades
  gracefully".
- **Explain why, once.** If a rule exists for a reason, give the reason in one sentence. Do not
  repeat it on the page.
- **Use "you" for the reader** and the imperative for steps. Use "HomeGAP" or "the orchestrator"
  for the software, not "we", except in "We recommend".
- **Vary the sentence length** inside the limits. Not every sentence has 20 words.
- **Use prose for explanations** and lists for real lists (options, steps, items to check). A
  page that is only bullets is not acceptable.
- **Bold is for UI labels and for the first use of a defined term.** Do not start each list item
  with a bold phrase. The exception is the feature list on the home page (and in the README),
  where each item starts with its point in bold.

### Do not use these patterns

They show that a text is generated, and they give no information:

- Marketing words: *seamless(ly)*, *robust*, *powerful*, *effortless(ly)*, *cutting-edge*,
  *game-changer*, *supercharge*, *unlock*, *elevate*, *streamline*, *empower*, *harness*,
  *delve*, *dive into*, *journey*, *landscape*, *realm*, *tapestry*, *crucial*, *vital*,
  *pivotal*, *essential* (as filler), *comprehensive*, *simply*, *just*, *easily*, *of course*,
  *it's worth noting*, *it's important to note*, *note that*.
- Openers and closers: "In this guide, ...", "Let's ...", "Whether you're ... or ...",
  "Welcome to ...", "In summary", "In conclusion", "That's it!", "Happy hosting!".
- Rhetorical questions as headings or openers ("Why does this matter?").
- Groups of three adjectives or three parallel phrases for rhythm only.
- "Not only ... but also ...", "It's not X, it's Y".
- Em dashes. Use a period, a comma, a colon or parentheses.
- Emoji, exclamation marks.
- A summary at the end of a page that repeats the page.
- Headings with a colon and a slogan ("Failover: Peace of Mind").

---

## Part 5: Page format (Zensical)

- One `#` heading at the top: the page title, in sentence case. Then one or two sentences that
  tell what the page is for.
- `##` for sections, `###` for subsections. Do not go deeper than `####`.
- Code blocks have a language: `yaml`, `bash`, `text`, `json`. Commands that a reader runs are in
  `bash` blocks, one command per line, without a `$` prompt.
- Admonitions: see Rule 7.1. Use `???` (a closed block) for long optional detail.
- Use content tabs (`=== "Standalone"` / `=== "Cluster"`) when one step is different in the two
  modes.
- Links between pages are relative paths to the `.md` file (`../cluster/index.md#the-vote`).
- Tables for reference data (labels, config keys, ports, API routes). Each cell that is a
  sentence follows the rules above.
- The `hg_version` variable (in double braces) gives the version of the docs. Pages go through
  Jinja: text that has double braces or a brace with a percent sign (for example Go templates)
  goes in a Jinja `raw` block.
- A screenshot that the maintainer must add is an HTML comment on its own line:
  `<!-- SCREENSHOT: docs/img/<page>/<nn>-<what>.png | what the image shows -->`.

---

## Part 6: Process for each page

1. **Isolate.** Divide the brief into descriptions and procedures.
2. **Draft.** Write the page in the order of the brief.
3. **Lint.** Find and replace the forbidden words, passive voice, "-ing" forms, hedges,
   semicolons and the patterns in Part 4.
4. **Measure.** Make each sentence fit the word limits. Make each paragraph six sentences or
   fewer.
5. **Check the facts.** Compare each statement with the brief or the source. Remove each statement
   that you cannot connect to a fact.
6. **Report.** After you write the files, report the files that you wrote, each `FACT-CHECK`
   comment and each fact in the brief that you think is wrong or unclear. Do not explain your
   edits.
