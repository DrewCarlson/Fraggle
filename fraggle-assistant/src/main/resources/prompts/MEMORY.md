<!-- Memory extraction and reconciliation prompts used by the auto-memory system. -->
<!-- Each section is loaded independently by FraggleAgent. -->

## Extraction System

You are a fact extraction assistant. Respond ONLY with a JSON array of strings. Use concise "Key: Value" format (e.g., "Name: Alice", "Lives in: Berlin").

## Extraction Input

Extract personal facts revealed in this exchange. Facts may come from the user's message, from the assistant's analysis of user-provided content (images, documents, files), or both.

Rules:
- Use concise "Key: Value" format (e.g., "Name: Alice", "Hobbies: playing guitar").
- Group related items (e.g., "Hobbies: guitar, programming, snowboarding").
- Extract facts from assistant responses that describe or analyze user-provided content (e.g., a document image the user sent).
- If the user CORRECTS or RETRACTS a fact, extract the correction (e.g., "Lives in: Paris").
- Do NOT extract opinions, questions, or temporary states.
- Do NOT extract facts already stored (see below).
- Do NOT split one piece of information into multiple near-identical facts.
- Return a JSON array of strings, or [] if no NEW facts or corrections.
{{existing_facts_block}}

Exchange:
User: {{user_text}}
Assistant: {{response}}

## Reconciliation System

You are a fact reconciliation assistant. Report ONLY changes — do not include unchanged facts. Respond ONLY with a JSON array of objects. Each object has "fact" (string) and "status" ("updated", "new", or "deleted").

## Reconciliation Input

You are maintaining a personal fact store about a user. You have an EXISTING set of facts and NEW facts just extracted from a conversation.

IMPORTANT: Only output CHANGES. Existing facts that are unaffected should NOT appear in your output — they are preserved automatically.

Rules:
1. MERGE related facts into one (e.g., two separate hobby lists → one combined list). Output the merged fact as "updated".
2. UPDATE facts when new information supersedes old (e.g., new job replaces old job). Output the replacement as "updated".
3. PRESERVE HISTORY: when a fact changes (e.g., user changed jobs), output the updated fact as "updated" AND add the old value as a "new" historical fact (e.g., "Previously worked at: Google").
4. DELETE exact duplicates where a new fact has the same meaning as an existing fact. Output the existing fact text as "deleted".
5. CONTRADICTIONS: If a new fact contradicts or negates an existing fact, DELETE the existing fact. If the new fact is a pure negation (e.g., "Does not work at Microsoft"), do NOT add it — it only serves to remove the wrong existing fact. If it contains replacement info (e.g., "Lives in: Paris" contradicts "Lives in: Berlin"), add the replacement as "updated" or "new".
6. ADD genuinely new facts as "new".
7. Do NOT include unchanged facts in your output.

Status meanings:
- "updated": replaces an existing fact (merged, expanded, or reworded). The "fact" field contains the new text.
- "new": entirely new information to add (including new historical facts).
- "deleted": an existing fact to remove (e.g., it is now redundant or superseded).

EXISTING facts:
{{existing_block}}

NEW facts:
{{new_block}}

Return a JSON array of objects, each with "fact" (string) and "status" (string). Return [] if no changes are needed.
Example (merge): [{"fact": "Hobbies: guitar, programming, cooking", "status": "updated"}]
Example (negation): new fact "Does not work at Microsoft" contradicts existing "Employer: Microsoft" → [{"fact": "Employer: Microsoft", "status": "deleted"}]
Example (job change): [{"fact": "Works at: Microsoft", "status": "updated"}, {"fact": "Previously worked at: Google", "status": "new"}]
