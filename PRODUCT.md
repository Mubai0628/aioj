# Product

## Register

product

## Users

AIOJ serves campus programming learners, teachers, and administrators.
Students use the student web app to browse problems, submit code, inspect
results, and ask the AI tutor for guided algorithm help. Teachers and admins use
separate management surfaces for problem authoring, testcase packages, AI draft
review, users, roles, and platform governance.

## Product Purpose

AIOJ is a teaching-oriented online judge. It is not a contest-only OJ or a
general cloud IDE. The product should help students close the loop from problem
reading to code submission to feedback, while keeping AI assistance educational,
auditable, and aligned with each student's current context.

Success looks like: students can practice without losing their place, understand
why a submission passed or failed, and get AI guidance that nudges thinking
forward instead of replacing learning.

## Brand Personality

Clear, focused, supportive.

The interface should feel like a calm study workspace: reliable enough for
serious programming practice, light enough for repeated daily use, and precise
enough that dense judge and AI information remains easy to scan.

## Anti-references

- Marketing landing-page composition inside authenticated product views.
- Oversized decorative panels that reduce working space.
- Empty tab/content regions that create unexplained blank space.
- Chat interfaces where history, messages, or input areas stretch the page
  instead of using independent scroll regions.
- AI tutor responses that repeatedly ask for information already present in the
  current conversation or active problem context.
- Memory extraction that stores one-off algorithm facts, AI suggestions, or
  temporary code analysis as long-term user preferences.

## Design Principles

1. Keep the task surface stable: navigation, history, messages, and inputs
   should each have predictable size and scroll behavior.
2. Make context visible but compact: problem, code, mode, and memory state should
   be shown as concise badges or short panels, not long explanations.
3. Prefer guided learning over answer dumping unless the student explicitly asks
   for a full answer.
4. Preserve standard product affordances: familiar forms, buttons, lists,
   dialogs, and keyboard behavior matter more than novelty.
5. Use restrained blue-white product styling with strong contrast, clear states,
   and minimal decorative motion.

## Accessibility & Inclusion

Target WCAG AA contrast for student-facing surfaces. Keyboard and focus behavior
should come from the existing React/Radix-style primitives or project-owned
components whenever possible. Motion should be short, state-driven, and safe
for reduced-motion users. Dense lists must use truncation and readable line
heights instead of overflowing text.
