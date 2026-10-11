from pathlib import Path
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer

out = Path(__file__).with_name("mode-reunion-cadrage.pdf")
styles = getSampleStyleSheet()
doc = SimpleDocTemplate(str(out), pagesize=A4, leftMargin=42, rightMargin=42,
                        topMargin=42, bottomMargin=42, title="DictAI — Mode Réunion")
story = [
    Paragraph("DictAI — Mode Réunion", styles["Title"]),
    Paragraph("Cadrage — fonction non implémentée", styles["Heading2"]),
    Spacer(1, 6),
    Paragraph("<b>1. Deux modes.</b> Dictée : usage individuel, sans diarisation. Réunion : transcription progressive avec Nemotron et analyse des voix.", styles["BodyText"]),
    Spacer(1, 4),
    Paragraph("<b>2. Jusqu’à huit voix.</b> Les profils apparaissent au fil des prises de parole (« Personne 1 », « Personne 2 »…). Chaque identité reste stable dans la réunion. Renommer une personne met à jour ses étiquettes passées et futures, sans remplacer de mots dans les phrases.", styles["BodyText"]),
    Spacer(1, 4),
    Paragraph("<b>3. Ignorer, puis réafficher.</b> Proposition : filtrer de façon réversible les interventions passées et futures ; cette portée reste à confirmer. Une voix ignorée reste suivie et compte toujours parmi les huit.", styles["BodyText"]),
    Spacer(1, 4),
    Paragraph("<b>4. Corriger pendant la transcription.</b> Les retouches et le curseur restent préservés pendant l’arrivée de nouveaux mots.", styles["BodyText"]),
    Spacer(1, 4),
    Paragraph("<b>5. Profils de réunion.</b> Les profils appartiennent à la réunion en cours. Une pause dans la même réunion les conserve.", styles["BodyText"]),
    Spacer(1, 4),
    Paragraph("<b>6. Délai et limites.</b> Le délai doit être court et mesuré sur Poco F7 ; aucun délai nul n’est promis. La diarisation seule ne résout pas les voix qui se couvrent.", styles["BodyText"]),
    Spacer(1, 4),
    Paragraph("<b>7. Preuve à obtenir.</b> Vérifier un vrai streaming sur Android puis effectuer des mesures prolongées sur Poco F7. Aucun résultat mobile n’est disponible à ce stade.", styles["BodyText"]),
    Spacer(1, 6),
    Paragraph("<b>Exemple</b><br/>Personne 1 : « Nous pouvons commencer. » → Sophie<br/>Personne 2 : « Le rendez-vous est mardi. » → Karim", styles["BodyText"]),
]
doc.build(story)
print(out)
