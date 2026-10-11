#!/usr/bin/env python3
"""Build the complete progressive multiformat research PDF from the source spec.

The script deliberately reads the Markdown source at build time.  Re-running it
therefore regenerates the PDF and the 150 dpi page renders after a source edit.
"""

from __future__ import annotations

import argparse
import html
import re
import shutil
import subprocess
from pathlib import Path
from typing import Iterable, Sequence

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Flowable,
    ListFlowable,
    ListItem,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[2]
SPEC_PATH = REPO_ROOT / "docs/superpowers/specs/2026-09-19-progressive-multiformat-postprocessing.md"
DEFAULT_PDF = SCRIPT_DIR / "progressive-multiformat-postprocessing.pdf"
DEFAULT_RENDER_DIR = SCRIPT_DIR / "renders"


def _first_existing(*paths: Path) -> Path | None:
    for path in paths:
        if path.is_file():
            return path
    return None


def register_fonts() -> tuple[str, str, str, str]:
    """Register a Unicode family, falling back to built-in PDF fonts."""

    regular = _first_existing(
        Path("/System/Library/Fonts/Supplemental/Arial.ttf"),
        Path("/Library/Fonts/Arial.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    )
    bold = _first_existing(
        Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf"),
        Path("/Library/Fonts/Arial Bold.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    )
    italic = _first_existing(
        Path("/System/Library/Fonts/Supplemental/Arial Italic.ttf"),
        Path("/Library/Fonts/Arial Italic.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf"),
    )
    if not (regular and bold and italic):
        return "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Courier"

    pdfmetrics.registerFont(TTFont("ResearchSans", str(regular)))
    pdfmetrics.registerFont(TTFont("ResearchSans-Bold", str(bold)))
    pdfmetrics.registerFont(TTFont("ResearchSans-Italic", str(italic)))
    return "ResearchSans", "ResearchSans-Bold", "ResearchSans-Italic", "Courier"


FONT_REGULAR, FONT_BOLD, FONT_ITALIC, FONT_MONO = register_fonts()


def pdf_safe_text(value: str) -> str:
    """Use ASCII hyphens in the PDF while retaining source wording."""

    return value.replace("\u2014", "-").replace("\u2013", "-").replace("\u2011", "-")


def inline_markup(value: str) -> str:
    """Convert the small Markdown inline subset used by the source to ReportLab XML."""

    value = pdf_safe_text(value)
    placeholders: dict[str, str] = {}

    def stash(markup: str) -> str:
        key = f"@@INLINE_{len(placeholders)}@@"
        placeholders[key] = markup
        return key

    def link_replacement(match: re.Match[str]) -> str:
        label = html.escape(pdf_safe_text(match.group(1)), quote=False)
        url = html.escape(match.group(2), quote=True)
        return stash(f'<a href="{url}" color="#1769aa">{label}</a>')

    value = re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)", link_replacement, value)

    def code_replacement(match: re.Match[str]) -> str:
        code = html.escape(pdf_safe_text(match.group(1)), quote=False)
        return stash(f'<font name="{FONT_MONO}">{code}</font>')

    value = re.sub(r"`([^`]+)`", code_replacement, value)

    def bold_replacement(match: re.Match[str]) -> str:
        return stash(f"<b>{html.escape(pdf_safe_text(match.group(1)), quote=False)}</b>")

    value = re.sub(r"\*\*([^*]+)\*\*", bold_replacement, value)

    def italic_replacement(match: re.Match[str]) -> str:
        return stash(f"<i>{html.escape(pdf_safe_text(match.group(1)), quote=False)}</i>")

    value = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", italic_replacement, value)
    value = html.escape(value, quote=False)
    for key, markup in placeholders.items():
        value = value.replace(key, markup)
    return value


def is_table_separator(line: str) -> bool:
    cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells)


def table_cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def is_special_start(line: str) -> bool:
    return bool(
        re.match(r"^#{1,6}\s+", line)
        or line.startswith("- ")
        or re.match(r"^\d+\.\s+", line)
        or ("|" in line)
    )


def parse_markdown(lines: Sequence[str]) -> Iterable[tuple[str, object]]:
    """Parse headings, paragraphs, lists and tables used by the research source."""

    index = 0
    while index < len(lines):
        line = lines[index].rstrip()
        if not line.strip():
            index += 1
            continue

        heading = re.match(r"^(#{1,6})\s+(.+)$", line)
        if heading:
            yield "heading", (len(heading.group(1)), heading.group(2).strip())
            index += 1
            continue

        if line.startswith("- "):
            items: list[str] = []
            while index < len(lines) and lines[index].lstrip().startswith("- "):
                items.append(lines[index].lstrip()[2:].strip())
                index += 1
            yield "bullet", items
            continue

        if re.match(r"^\d+\.\s+", line):
            items = []
            while index < len(lines) and re.match(r"^\d+\.\s+", lines[index]):
                items.append(re.sub(r"^\d+\.\s+", "", lines[index]).strip())
                index += 1
            yield "numbered", items
            continue

        if index + 1 < len(lines) and "|" in line and is_table_separator(lines[index + 1]):
            rows = [table_cells(line)]
            index += 2
            while index < len(lines) and "|" in lines[index] and lines[index].strip():
                rows.append(table_cells(lines[index]))
                index += 1
            yield "table", rows
            continue

        paragraph_lines = [line.strip()]
        index += 1
        while index < len(lines):
            candidate = lines[index].rstrip()
            if not candidate.strip() or is_special_start(candidate):
                break
            paragraph_lines.append(candidate.strip())
            index += 1
        yield "paragraph", " ".join(paragraph_lines)


class PipelineDiagram(Flowable):
    """Compact first-page pipeline diagram required by the research brief."""

    def __init__(self) -> None:
        super().__init__()
        self.height = 93
        self.width = 0

    def wrap(self, available_width: float, available_height: float) -> tuple[float, float]:
        self.width = available_width
        return available_width, self.height

    def draw(self) -> None:
        canvas = self.canv
        box_width = min(78, (self.width - 5 * 12) / 6)
        box_height = 46
        gap = (self.width - 6 * box_width) / 5
        start_x = 0
        y = 34
        labels = [
            ("Voix", colors.HexColor("#e8f2ff")),
            ("Nemotron\nASR", colors.HexColor("#e7f7f0")),
            ("Segmentation\nstable", colors.HexColor("#fff4d6")),
            ("Modèle\nmultiformat", colors.HexColor("#f2eaff")),
            ("Validation", colors.HexColor("#ffe8dc")),
            ("Overlay\nmodifiable", colors.HexColor("#e3f3ff")),
        ]
        label_style = ParagraphStyle(
            "diagram-label",
            fontName=FONT_BOLD,
            fontSize=7.6,
            leading=9.2,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#182a3a"),
        )
        for position, (label, fill) in enumerate(labels):
            x = start_x + position * (box_width + gap)
            canvas.setFillColor(fill)
            canvas.setStrokeColor(colors.HexColor("#557189"))
            canvas.setLineWidth(0.8)
            canvas.roundRect(x, y, box_width, box_height, 6, fill=1, stroke=1)
            paragraph = Paragraph(label.replace("\n", "<br/>").replace("-", "-"), label_style)
            text_width, text_height = paragraph.wrap(box_width - 8, box_height - 8)
            paragraph.drawOn(canvas, x + 4, y + (box_height - text_height) / 2)
            if position < len(labels) - 1:
                arrow_x = x + box_width + 2
                canvas.setStrokeColor(colors.HexColor("#557189"))
                canvas.setLineWidth(1.2)
                canvas.line(arrow_x, y + box_height / 2, arrow_x + gap - 4, y + box_height / 2)
                canvas.line(arrow_x + gap - 4, y + box_height / 2, arrow_x + gap - 9, y + box_height / 2 + 3)
                canvas.line(arrow_x + gap - 4, y + box_height / 2, arrow_x + gap - 9, y + box_height / 2 - 3)

        note_style = ParagraphStyle(
            "diagram-note",
            fontName=FONT_BOLD,
            fontSize=8.2,
            leading=10,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#8b3d16"),
        )
        note = Paragraph("Corrections humaines prioritaires à chaque mise à jour", note_style)
        note_width, note_height = note.wrap(self.width, 18)
        note.drawOn(canvas, 0, 8)


def build_styles() -> dict[str, ParagraphStyle]:
    sample = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "research-title",
            parent=sample["Title"],
            fontName=FONT_BOLD,
            fontSize=20,
            leading=23,
            textColor=colors.HexColor("#17324d"),
            spaceAfter=7,
        ),
        "meta": ParagraphStyle(
            "research-meta",
            parent=sample["Normal"],
            fontName=FONT_ITALIC,
            fontSize=9.2,
            leading=12.5,
            textColor=colors.HexColor("#4b5d6b"),
            spaceAfter=8,
        ),
        "h2": ParagraphStyle(
            "research-h2",
            parent=sample["Heading2"],
            fontName=FONT_BOLD,
            fontSize=14,
            leading=17,
            textColor=colors.HexColor("#17324d"),
            spaceBefore=12,
            spaceAfter=5,
            keepWithNext=True,
        ),
        "h3": ParagraphStyle(
            "research-h3",
            parent=sample["Heading3"],
            fontName=FONT_BOLD,
            fontSize=11.5,
            leading=14,
            textColor=colors.HexColor("#2f5877"),
            spaceBefore=9,
            spaceAfter=4,
            keepWithNext=True,
        ),
        "body": ParagraphStyle(
            "research-body",
            parent=sample["BodyText"],
            fontName=FONT_REGULAR,
            fontSize=9.2,
            leading=12.4,
            textColor=colors.HexColor("#1f2b33"),
            spaceAfter=5.5,
            alignment=TA_LEFT,
            splitLongWords=False,
        ),
        "bullet": ParagraphStyle(
            "research-bullet",
            parent=sample["BodyText"],
            fontName=FONT_REGULAR,
            fontSize=8.9,
            leading=11.7,
            leftIndent=14,
            firstLineIndent=0,
            textColor=colors.HexColor("#1f2b33"),
            spaceAfter=3,
            splitLongWords=False,
        ),
        "table": ParagraphStyle(
            "research-table",
            parent=sample["BodyText"],
            fontName=FONT_REGULAR,
            fontSize=7.8,
            leading=9.9,
            textColor=colors.HexColor("#1f2b33"),
            splitLongWords=False,
        ),
        "table-header": ParagraphStyle(
            "research-table-header",
            parent=sample["BodyText"],
            fontName=FONT_BOLD,
            fontSize=8,
            leading=10,
            textColor=colors.white,
            splitLongWords=False,
        ),
    }


def make_table(rows: Sequence[Sequence[str]], styles: dict[str, ParagraphStyle], available_width: float) -> Table:
    max_columns = max(len(row) for row in rows)
    normalized = [list(row) + [""] * (max_columns - len(row)) for row in rows]
    cells: list[list[Paragraph]] = []
    for row_index, row in enumerate(normalized):
        style = styles["table-header"] if row_index == 0 else styles["table"]
        cells.append([Paragraph(inline_markup(cell), style) for cell in row])

    if max_columns == 2:
        widths = [available_width * 0.21, available_width * 0.79]
    else:
        widths = [available_width / max_columns] * max_columns

    table = Table(cells, colWidths=widths, repeatRows=1, hAlign="LEFT", splitByRow=1)
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#315b7d")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                ("BACKGROUND", (0, 1), (-1, -1), colors.HexColor("#f4f7f9")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.HexColor("#f4f7f9"), colors.white]),
                ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#b6c4ce")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 5),
                ("RIGHTPADDING", (0, 0), (-1, -1), 5),
                ("TOPPADDING", (0, 0), (-1, -1), 4),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ]
        )
    )
    return table


def add_source_flowables(story: list[Flowable], source_text: str, styles: dict[str, ParagraphStyle], available_width: float) -> None:
    lines = source_text.splitlines()
    if not lines:
        raise ValueError("La source spec est vide")

    first_heading = re.match(r"^#\s+(.+)$", lines[0].strip())
    if not first_heading:
        raise ValueError("La première ligne du spec doit être un titre Markdown de niveau 1")
    story.append(Paragraph(inline_markup(first_heading.group(1)), styles["title"]))
    story.append(Paragraph(inline_markup(lines[2].strip()), styles["meta"]))
    story.append(Paragraph("Schéma du traitement progressif proposé", styles["h3"]))
    story.append(PipelineDiagram())
    story.append(Spacer(1, 4))

    for kind, payload in parse_markdown(lines[4:]):
        if kind == "heading":
            level, text = payload  # type: ignore[misc]
            style = styles["h2"] if level == 2 else styles["h3"]
            story.append(Paragraph(inline_markup(text), style))
        elif kind == "paragraph":
            story.append(Paragraph(inline_markup(payload), styles["body"]))  # type: ignore[arg-type]
        elif kind in ("bullet", "numbered"):
            items = [
                ListItem(Paragraph(inline_markup(item), styles["bullet"]), leftIndent=0)
                for item in payload  # type: ignore[union-attr]
            ]
            bullet_type = "bullet" if kind == "bullet" else "1"
            story.append(
                ListFlowable(
                    items,
                    bulletType=bullet_type,
                    start="circle" if kind == "bullet" else 1,
                    leftIndent=13,
                    bulletFontName=FONT_REGULAR,
                    bulletFontSize=8,
                    bulletOffsetY=1,
                    spaceAfter=4,
                )
            )
        elif kind == "table":
            table = make_table(payload, styles, available_width)  # type: ignore[arg-type]
            story.append(table)
            story.append(Spacer(1, 6))


def draw_header_footer(canvas, document) -> None:  # type: ignore[no-untyped-def]
    canvas.saveState()
    page_width, page_height = A4
    canvas.setStrokeColor(colors.HexColor("#d1dce3"))
    canvas.setLineWidth(0.5)
    canvas.line(document.leftMargin, page_height - 27, page_width - document.rightMargin, page_height - 27)
    canvas.setFont(FONT_REGULAR, 7.2)
    canvas.setFillColor(colors.HexColor("#60717d"))
    canvas.drawString(document.leftMargin, page_height - 20, "DictAI - recherche et spécification")
    canvas.drawRightString(page_width - document.rightMargin, page_height - 20, "19 septembre 2026")
    canvas.line(document.leftMargin, 28, page_width - document.rightMargin, 28)
    canvas.drawCentredString(page_width / 2, 17, f"Page {document.page}")
    canvas.restoreState()


def locate_pdftoppm() -> str:
    candidates = [
        shutil.which("pdftoppm"),
        "/Users/ulliemaillot/.cache/codex-runtimes/codex-primary-runtime/dependencies/poppler/bin/pdftoppm",
        "/opt/homebrew/bin/pdftoppm",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return candidate
    raise RuntimeError("pdftoppm est introuvable ; impossible de produire les rendus PNG")


def render_pages(pdf_path: Path, render_dir: Path) -> None:
    render_dir.mkdir(parents=True, exist_ok=True)
    for stale in render_dir.glob("page-*.png"):
        trash = shutil.which("trash")
        if trash:
            subprocess.run([trash, str(stale)], check=True)
        else:
            stale.unlink()
    pdftoppm = locate_pdftoppm()
    prefix = render_dir / "page"
    subprocess.run(
        [pdftoppm, "-png", "-r", "150", str(pdf_path), str(prefix)],
        check=True,
        capture_output=True,
        text=True,
    )
    generated = sorted(render_dir.glob("page-*.png"))
    if not generated:
        raise RuntimeError("pdftoppm n'a produit aucune page PNG")


def build(pdf_path: Path, render_dir: Path) -> None:
    source_text = SPEC_PATH.read_text(encoding="utf-8")
    pdf_path.parent.mkdir(parents=True, exist_ok=True)
    styles = build_styles()
    document = SimpleDocTemplate(
        str(pdf_path),
        pagesize=A4,
        rightMargin=17 * mm,
        leftMargin=17 * mm,
        topMargin=15 * mm,
        bottomMargin=14 * mm,
        title="DictAI - fine-tuning multiformat et correction pendant la dictée",
        author="DictAI",
        subject="Recherche et spécification du post-traitement progressif multiformat",
        allowSplitting=1,
    )
    story: list[Flowable] = []
    available_width = A4[0] - document.leftMargin - document.rightMargin
    add_source_flowables(story, source_text, styles, available_width)
    document.build(story, onFirstPage=draw_header_footer, onLaterPages=draw_header_footer)
    render_pages(pdf_path, render_dir)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, default=DEFAULT_PDF, help="Chemin du PDF de sortie")
    parser.add_argument("--renders", type=Path, default=DEFAULT_RENDER_DIR, help="Dossier des PNG 150 dpi")
    args = parser.parse_args()
    build(args.pdf.resolve(), args.renders.resolve())
    print(f"PDF: {args.pdf.resolve()}")
    print(f"Rendus PNG: {args.renders.resolve()}")
    print(f"Pages PNG: {len(list(args.renders.resolve().glob('page-*.png')))}")


if __name__ == "__main__":
    main()
