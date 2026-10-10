# Report build

`REPORT.md` is the source. `REPORT.pdf` is generated — edit the Markdown, never the PDF.

## Rebuild the PDF

```bash
pandoc REPORT.md -o REPORT.pdf --pdf-engine=lualatex --include-in-header=header.tex \
  --toc --toc-depth=2 --resource-path=.:diagrams \
  -V geometry:"a4paper,margin=2.2cm" -V mainfont="Calibri" -V monofont="Consolas" \
  -V monofontoptions="Scale=0.8" -V fontsize=11pt -V colorlinks=true -V linkcolor=black \
  -V urlcolor="[rgb]{0,0.25,0.55}" -V papersize=a4
```

Needs pandoc 3.x and a LaTeX distribution with LuaLaTeX (MiKTeX or TeX Live).

**Do not add `--number-sections`:** the headings already carry their own numbers, and the flag
produces "6 5. Rubric justification".

**Do not load `xcolor` with the `[table]` option in `header.tex`:** that pulls in `colortbl`,
which breaks pandoc's `longtable` column specifications and fails the build with
`Undefined control sequence ... \insert@pcolumn`.

## Rebuild the diagrams

Sources are `diagrams/*.md` (Mermaid in a fenced block). Rendered with mermaid-cli:

```bash
npm install @mermaid-js/mermaid-cli@11.4.2
npx mmdc -i diagrams/01-system-architecture.md -o diagrams/01-system-architecture.png \
  -t neutral -b white -w 2200 --scale 2
```

Note that `stateDiagram-v2` transition labels are **plain text** — `<br/>`, `<i>` and HTML
entities such as `&mdash;` are parse errors there, and entities also break sequence-diagram
notes. Flowcharts accept them.

## Regenerate the commit logs

```bash
git log --all --pretty=fuller --stat > commit-log.txt
# commit-log.csv is produced by the snippet in the session notes; columns are
# sha, author_name, author_email, author_date, committer_name, committer_date, subject
```
