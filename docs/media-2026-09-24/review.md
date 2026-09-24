# Revue finale des captures et blocs d’images

Les deux passes Astra portent sur les mêmes 17 fichiers de production figés, au-dessus du commit `26cb80d`. Le [manifeste des fichiers](evidence/production-snapshot.json) consigne leurs empreintes SHA-256. Son empreinte agrégée est `2cdd810a00259a1795963ca6ba9dea04b721f07a7d451e9da2d116396ae5431b`. Une nouvelle lecture après la deuxième passe confirme qu’aucun de ces fichiers n’a changé.

## Passe 1 — fonctionnel, interface et sécurité

La capture mémorise le curseur avant la perte de focus. La validation ajoute tous les UUID de la série à ce point du texte ; la session reste disponible jusqu’à l’enregistrement des références. Le même facteur de zoom est appliqué au viseur Camera2 et à la photo. Le scanner libère la caméra avant son lancement et importe ses pages dans la série, dans la capacité restante.

Les objets de l’éditeur sont atomiques, sélectionnables et déplaçables ; le menu propose une alternative au glissement. Les captures d’écran disposent d’une barre flottante et les fenêtres de capture sont masquées avant la prise. L’acceptation, l’annulation, le retour à la dictée et les erreurs de stockage ont été suivis dans le service.

Les images restent dans le stockage local et leurs noms sont contrôlés par UUID. Leur nombre et leur décodage sont bornés. La publication tardive d’une image est refusée après annulation. Le texte destiné à l’insertion ou au traitement vocal exclut les objets internes. L’export HTML échappe le texte et utilise une politique de contenu sans script ni accès réseau.

Les six aperçus caméra, le bloc natif, les trois pages PDF et les variantes HTML ont été inspectés. L’audit visuel complémentaire de l’éditeur du service (320 × 104 px) et de la barre de captures (360 × 92 px) confirme leur compacité et la lisibilité des commandes. Aucun constat bloquant sur cette passe.

## Passe 2 — qualité, régressions et cas défavorables

La conversion entre texte brut, positions affichées et sérialisation historique est centralisée. L’ordre à un même emplacement, les séparateurs des anciennes notes et l’identité des images sont conservés. La suppression d’un bloc retire ses références persistantes ; le retrait d’une image d’un groupe conserve les autres.

Les mises à jour de transcription utilisent un remplacement limité du texte et préservent les spans étrangers, notamment ceux de composition du clavier. Les gardes de capture couvrent aussi une série validée dont le nettoyage doit être réessayé. Une reprise réenregistre les métadonnées et tolère un changement d’identifiant de groupe ; elle ne peut pas supprimer les images en fermant le brouillon. L’échec de copie dans Photos conserve une note de secours et rétablit le mode Message.

Les dimensions d’export sont partagées entre PDF et HTML. Le traitement d’une image à la fois et l’encodage base64 en flux limitent la mémoire utilisée. Les effets sur la capture audio, la sélection de texte, les anciennes notes et l’insertion externe ont été relus. Aucun constat bloquant sur cette passe.

## Limites de la validation

Les gestes tactiles sur Poco F7/Pad 7 et le parcours fourni par le SDK Google Scanner restent à vérifier sur ces appareils. Une capture d’écran extrêmement haute reste compacte dans le document et nécessite le zoom PDF ou l’agrandissement HTML pour lire ses petits caractères. Les résultats exacts de compilation et de tests sont consignés dans le [compte rendu principal](../2026-09-24-capture-images.md).
