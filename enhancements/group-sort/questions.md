# Questions: group-sort

## Q1 spec -> user
Where should the option live? The spec puts it in Settings > Appearance (AC-1) so no existing
screen changes. The alternative is a sort choice inside the reorder dialog, which is easier
to find but changes that dialog.

## Q2 spec -> user
With a name order selected, should the reorder dialog stay usable? The spec keeps it working
with a notice (AC-10). The alternative is to disable it until *Manual* is selected again.

## Q3 spec -> user
Should numbers in names sort by value ("Mix 2" before "Mix 10", AC-5), or strictly character
by character ("Mix 10" before "Mix 2")?

## A1 user
"Settings > Appearance"
Applied: unchanged (AC-1).

## A2 user
"Reorder dialog." Follow-up choice: "Stay usable with a notice".
Applied: unchanged (AC-10).

## A3 user
"By character. User needs to create groups like 001 - First, ZZZ - Last, ZZZZ - Last of
last."
Applied: plain character-by-character comparison, no number values and no language-specific
rules, so prefixes like these sort predictably (AC-5).

## Q4 plan -> spec
AC-5 fixes the order of digits, A-Z and letters outside A-Z, but not where spaces and symbols
sort. The plan assumes plain character-code order after folding letters to lower case: space and
`! " # $ % & ' ( ) * + , - . /` before digits; `: ; < = > ? @ [ \ ] ^ _` and the backtick
between digits and letters; `{ | } ~` after "z" but before "Ä". Example: "- Top" < "001" <
"[old]" < "_misc" < "A Team" < "A-Team" < "music" < "~last" < "Äpfel". Is that the intended
order? The examples already in AC-5 sort the same either way; only names that start with or
contain symbols are affected (AC-5, and through it AC-3/AC-4).

## A4 spec
Yes. "By character" (A3) means plain character-code order after ignoring case, symbols and
spaces included, exactly as the plan assumed. AC-5 now says so and has an example with a
leading symbol. The plan's algorithm and fixture F4 stand.
