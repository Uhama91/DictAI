from __future__ import annotations

import html
import subprocess
import textwrap
import xml.etree.ElementTree as ET
from pathlib import Path

import markdown
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.platypus import (
    HRFlowable,
    ListFlowable,
    ListItem,
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[4]
OUT_DIR = Path(__file__).resolve().parent
SPEC = ROOT / "docs/superpowers/specs/2026-09-24-meeting-mode.md"
PLAN = ROOT / "docs/superpowers/plans/2026-09-24-meeting-mode.md"
PDF = OUT_DIR / "mode-reunion-spec-plan-complets.pdf"
PAGE_WIDTH = A4[0] - 84

SHEETS = getSampleStyleSheet()
for heading_level in range(1, 7):
    SHEETS[f"Heading{heading_level}"].keepWithNext = True
BODY = ParagraphStyle(
    "SourceBody", parent=SHEETS["BodyText"], fontName="Helvetica",
    fontSize=9.2, leading=12, alignment=TA_LEFT, spaceAfter=5,
    splitLongWords=1, allowWidows=0, allowOrphans=0,
)
QUOTE = ParagraphStyle(
    "SourceQuote", parent=BODY, leftIndent=12, rightIndent=8,
    textColor=colors.HexColor("#4C5966"), borderColor=colors.HexColor("#BEC8D0"),
    borderWidth=0.6, borderPadding=5, spaceBefore=3, spaceAfter=7,
)
CODE = ParagraphStyle(
    "SourceCode", fontName="Courier", fontSize=7.2, leading=9,
    leftIndent=5, rightIndent=5, borderColor=colors.HexColor("#C9D1D7"),
    borderWidth=0.5, borderPadding=5, backColor=colors.HexColor("#F3F5F6"),
    spaceBefore=4, spaceAfter=7,
)
CELL = ParagraphStyle("SourceCell", parent=BODY, fontSize=8.1, leading=10)
TH = ParagraphStyle(
    "SourceTH", parent=CELL, fontName="Helvetica-Bold",
    textColor=colors.HexColor("#183044"),
)


def tag(node: ET.Element) -> str:
    return node.tag.rsplit("}", 1)[-1].lower()


def inline(node: ET.Element) -> str:
    pieces = [html.escape(node.text or "")]
    for child in node:
        name = tag(child)
        content = inline(child)
        if name in ("strong", "b"):
            content = f"<b>{content}</b>"
        elif name in ("em", "i"):
            content = f"<i>{content}</i>"
        elif name == "code":
            content = f'<font name="Courier" size="8">{content}</font>'
        elif name in ("del", "s", "strike"):
            content = f"<strike>{content}</strike>"
        elif name == "a":
            href = html.escape(child.attrib.get("href", ""), quote=True)
            content = f'<link href="{href}" color="#145D78">{content}</link>'
        elif name == "br":
            content = "<br/>"
        elif name == "img":
            content = html.escape(child.attrib.get("alt", "[image]"))
        elif name == "p":
            content = f"{content}<br/>"
        pieces.extend((content, html.escape(child.tail or "")))
    return "".join(pieces)


def code_block(node: ET.Element):
    raw = "".join(node.itertext()).rstrip("\n")
    lines = []
    for line in raw.splitlines():
        indent = len(line) - len(line.lstrip(" "))
        wrapped = textwrap.wrap(
            line, width=96, break_long_words=True, break_on_hyphens=False,
            subsequent_indent=" " * (indent + 2), replace_whitespace=False,
            drop_whitespace=False,
        )
        lines.extend(wrapped or [""])
    # Preformatted draws literal text; HTML-escaping here would expose entities
    # such as &quot; and &gt; in the printed code.
    return Preformatted("\n".join(lines), CODE)


def blocks_in_list_item(node: ET.Element):
    result = []
    pending = ET.Element("span")
    pending.text = node.text or ""

    def flush():
        text = inline(pending).strip()
        if text:
            result.append(Paragraph(text, BODY))
        pending.clear()

    for child in node:
        if tag(child) in ("ul", "ol", "blockquote", "pre", "table"):
            flush()
            result.extend(convert_block(child))
        else:
            pending.append(child)
        if child.tail:
            pending.tail = (pending.tail or "") + child.tail
    flush()
    return result or [Paragraph("", BODY)]


def list_block(node: ET.Element):
    ordered = tag(node) == "ol"
    items = [ListItem(blocks_in_list_item(item), leftIndent=2) for item in node if tag(item) == "li"]
    if not items:
        return []
    options = dict(
        bulletType="1" if ordered else "bullet",
        leftIndent=18, bulletFontName="Helvetica", bulletFontSize=8.5,
        bulletDedent=10, bulletColor=colors.HexColor("#1C586B"),
        spaceBefore=2, spaceAfter=6,
    )
    if ordered:
        options["start"] = node.attrib.get("start", "1")
    return [ListFlowable(
        items, **options,
    )]


def table_block(node: ET.Element):
    rows = []
    header_count = 0
    for section in node:
        if tag(section) not in ("thead", "tbody", "tfoot", "tr"):
            continue
        tr_nodes = [section] if tag(section) == "tr" else [r for r in section if tag(r) == "tr"]
        for tr in tr_nodes:
            cells = []
            for cell in tr:
                if tag(cell) not in ("th", "td"):
                    continue
                style = TH if tag(cell) == "th" else CELL
                cells.append(Paragraph(inline(cell).strip(), style))
            if cells:
                rows.append(cells)
                if tag(section) == "thead" or all(tag(cell) == "th" for cell in tr):
                    header_count = len(rows)
    if not rows:
        return []
    columns = max(map(len, rows))
    data = [row + [Paragraph("", CELL)] * (columns - len(row)) for row in rows]
    t = Table(data, colWidths=[PAGE_WIDTH / columns] * columns,
              repeatRows=header_count, hAlign="LEFT", splitByRow=1)
    commands = [
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#A9B5BE")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("ROWBACKGROUNDS", (0, header_count), (-1, -1),
         [colors.white, colors.HexColor("#F4F6F7")]),
    ]
    if header_count:
        commands.extend([
            ("BACKGROUND", (0, 0), (-1, header_count - 1), colors.HexColor("#E5EBEF")),
            ("LINEBELOW", (0, header_count - 1), (-1, header_count - 1), 0.8,
             colors.HexColor("#788B98")),
        ])
    t.setStyle(TableStyle(commands))
    t.spaceBefore, t.spaceAfter = 5, 8
    return [t]


def convert_block(node: ET.Element):
    name = tag(node)
    if name in ("h1", "h2", "h3", "h4", "h5", "h6"):
        style = SHEETS["Heading" + name[1]]
        return [Paragraph(inline(node).strip(), style)]
    if name == "p":
        text = inline(node).strip()
        return [Paragraph(text, BODY)] if text else []
    if name in ("ul", "ol"):
        return list_block(node)
    if name == "pre":
        return [code_block(node)]
    if name == "table":
        return table_block(node)
    if name == "blockquote":
        result = []
        for child in node:
            for block in convert_block(child):
                if isinstance(block, Paragraph):
                    block.style = QUOTE
                result.append(block)
        return result
    if name == "hr":
        return [HRFlowable(width="100%", thickness=0.6, color=colors.HexColor("#A9B5BE"),
                           spaceBefore=5, spaceAfter=8)]
    if name in ("div", "section"):
        return [block for child in node for block in convert_block(child)]
    return []


def parse_document(path: Path):
    source = path.read_text(encoding="utf-8")
    rendered = markdown.markdown(source, extensions=["tables", "fenced_code", "sane_lists"])
    root = ET.fromstring(f"<root>{rendered}</root>")
    blocks = []
    for child in root:
        if (
            path == PLAN
            and tag(child) in ("h1", "h2", "h3", "h4", "h5", "h6")
            and "".join(child.itertext()).strip().startswith("T8b —")
        ):
            # Keep the short publication appendix together instead of leaving
            # its final two lines orphaned on a mostly empty page.
            blocks.append(PageBreak())
        blocks.extend(convert_block(child))
    return blocks


def draw_page_number(canvas, document):
    canvas.saveState()
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(colors.HexColor("#71808A"))
    canvas.drawRightString(A4[0] - 42, 22, f"Page {document.page}")
    canvas.restoreState()


def main():
    if not SPEC.is_file() or not PLAN.is_file():
        raise FileNotFoundError("Les deux documents source requis sont introuvables.")
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    story = [
        Spacer(1, 92),
        Paragraph("Documents sources complets", SHEETS["Title"]),
        Spacer(1, 24),
        Paragraph("DictAI — Mode Réunion en direct", SHEETS["Heading2"]),
        Paragraph(
            "Source : docs/superpowers/specs/2026-09-24-meeting-mode.md<br/>"
            "Rédaction et arbitrage : Astra, agent principal ; implémentation autorisée par l’utilisateur.",
            BODY,
        ),
        Spacer(1, 18),
        Paragraph("Mode Réunion en direct — plan d’implémentation", SHEETS["Heading2"]),
        Paragraph(
            "Source : docs/superpowers/plans/2026-09-24-meeting-mode.md<br/>"
            "Auteur : Astra, agent principal de la conversation. Rédaction non déléguée. 24 septembre 2026.",
            BODY,
        ),
        PageBreak(),
    ]
    story.extend(parse_document(SPEC))
    story.append(PageBreak())
    story.extend(parse_document(PLAN))

    doc = SimpleDocTemplate(
        str(PDF), pagesize=A4, leftMargin=42, rightMargin=42,
        topMargin=42, bottomMargin=42, title="DictAI — Mode Réunion : spécification et plan complets",
        author="Astra, agent principal de la conversation",
    )
    doc.build(story, onFirstPage=draw_page_number, onLaterPages=draw_page_number)

    prefix = OUT_DIR / "preview"
    subprocess.run(["pdftoppm", "-png", "-r", "150", str(PDF), str(prefix)], check=True)
    pages = sorted(OUT_DIR.glob("preview-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1]))
    readme = [
        "# Aperçus — spécification et plan complets",
        "",
        "Le PDF contient les deux sources intégrales d’Astra, sans modification de leur texte.",
        "",
        "[Ouvrir le PDF](mode-reunion-spec-plan-complets.pdf)",
        "",
        f"Pages : {len(pages)}. Rendu PNG à 150 dpi.",
    ]
    for number, page in enumerate(pages, 1):
        final = OUT_DIR / f"page-{number:03}.png"
        page.replace(final)
        readme.extend(["", f"## Page {number}", "", f"![Page {number}]({final.name})"])
    (OUT_DIR / "README.md").write_text("\n".join(readme) + "\n", encoding="utf-8")
    print(f"PDF: {PDF}")
    print(f"PNG pages: {len(pages)}")


if __name__ == "__main__":
    main()
