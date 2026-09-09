# DictAI 0.9.1 — collage image dans la conversation et caméra interne

## Problème et parcours obtenu

Ullie utilise l’application Android **ChatGPT → Remote**. Le parcours 0.9.0 proposait automatiquement un export dès qu’une note contenait une image, au lieu de préparer une pièce jointe dans la conversation déjà ouverte. L’appareil photo externe masquait volontairement la pastille et pouvait la laisser invisible en cas d’interruption.

0.9.1 supprime la redirection automatique. Une capture ou photo est sauvegardée dans la note, puis une copie JPEG numérotée est préparée et une action de collage est demandée au champ présent au geste. La dictée finale est insérée normalement avec ses repères `[[Image N]]`. Aucun bouton d’envoi de message n’est activé. PDF, HTML et partage groupé restent des actions d’export explicites.

La caméra est un viseur Camera2 dans une petite activité de dialogue, appartenant à DictAI, au-dessus de l’application précédente. La pastille reste visible ; le panneau de transcription est masqué pendant le cadrage, le micro est mis en pause puis repris. La permission caméra Android est demandée au premier usage. Retour, Annuler, interruption et refus de permission conservent le texte et retirent le repère sans image. L’aperçu utilise au plus 1280 pixels de côté quand le matériel le propose, le JPEG au plus 2048 ; sinon la plus petite taille prise en charge est choisie. L’import conserve la borne 2048. Aucune application caméra externe ni galerie n’est ouverte. Les ressources caméra sont libérées quand le viseur quitte le premier plan.

## Collage ciblé, sans garantie universelle

Le service mémorise en mémoire le dernier champ externe éditable et en prend une référence au moment du geste. Si un unique éditeur visible est disponible, il peut aussi être retrouvé sans exiger que le clavier soit déjà affiché. Avant le collage : même fenêtre active, même paquet, nœud encore valide et visible, champ éditable non sensible, focus obtenu et sélection connue réduite au curseur. Un champ changé ou une sélection active bloque l’action.

La copie dans le presse-papier est un `ClipData` de type `image/jpeg`, avec URI FileProvider et **texte de repli vide**. Un éditeur qui n’accepte que le texte ne doit donc pas recevoir un chemin `content://` ni une chaîne base64. Android gère les permissions de lecture du presse-papier ; le fournisseur n’expose que les dossiers cache autorisés. Une nouvelle copie est immutable par tentative, et les anciennes copies de plus de sept jours sont nettoyées.

Le système tente ACTION_PASTE, sans automatiser les boutons de pièces jointes d’une application. L’existence du collage texte ou d’un bouton Ajouter une photo dans ChatGPT, Messenger, WhatsApp, Instagram ou Claude **ne garantit pas** que son éditeur accepte une image depuis cette API. Certaines applications ne prennent en charge l’image que par un clavier utilisant `InputConnection.commitContent` : DictAI n’impose ni n’active un nouveau clavier dans cette version. Un refus ne provoque pas de menu de partage ni de repli cloud ; l’image reste dans la note. Le maintien sur sa miniature permet de réessayer volontairement dans le champ ouvert, avec un nouveau contrôle de destination.

Les images sont tentées au moment des captures, pas recollées automatiquement à chaque export ultérieur du texte. Une note rouverte peut donc transférer son texte directement et recoller ses images par leurs miniatures, ou utiliser le partage explicite. Aucune compatibilité de réception réelle n’est encore certifiée dans les applications cibles.

Si l’utilisateur termine immédiatement après une capture, la finalisation attend la fin de la tentative de collage. Une attente supplémentaire plafonnée à 1200 ms laisse le destinataire ouvrir le fichier avant que la copie du texte ne remplace le presse-papier image. Ce délai n’est pas ajouté à chaque dictée ; il ne concerne que cette chevauchée image/fin de dictée.

## Diagnostic pour le prochain essai sur téléphone

**Dernier collage d’image** dans les réglages donne la version, la date, le numéro de l’image, le paquet destinataire, l’issue de l’action et la lecture éventuelle du fichier par l’UID du destinataire. Si Android ne permet pas d’identifier cet UID, la lecture est indiquée comme non identifiable. Une lecture par le clavier ou l’interface système n’est pas assimilée à une lecture par ChatGPT. Ni l’action acceptée ni l’ouverture du fichier ne prouvent qu’une pièce jointe est visible : l’interface du destinataire doit le confirmer.

Ce diagnostic exclut image, dictée, note, URI, vocabulaire et clé. Les originaux sont conservés dans la note. Les pixels sont fournis à l’application destinataire uniquement à la demande de capture/collage d’Ullie ; le moteur de correction textuelle ne les reçoit pas.

## Vérification

Tests JVM : fenêtres et champs invalides, sélections à protéger, tailles des flux caméra, orientation des JPEG et proportions de l’aperçu. Un test Android contrôlé vérifie le payload image, son texte de repli vide, la lecture du JPEG et sa réception par un éditeur instrumenté. Ce test est compilé ; il ne remplace pas un essai des applications externes.

Construction locale finale réussie : **396 tests JVM dans 59 suites**, zéro échec, erreur ou test sauté. APK application et tests Android compilés. Signature et alignement ZIP / dix bibliothèques natives 16 Ko vérifiés. APK locale : 79 805 950 octets, SHA-256 `feb70ff27b405605b9c504a11983290b18a6367a2a319b2b590178d857425ada`. Publication vérifiée : [APK directe 0.9.1](https://github.com/Uhama91/DictAI/releases/download/gemma-test-34355763495/dictai-local-layout-test.apk), [Actions réussie](https://github.com/Uhama91/DictAI/actions/runs/34355763495), source `be1c92c6b4149f4c64f0fe1c642d41daed65faea`. Téléchargement public intégral HTTP 200 : **78 800 260 octets**, SHA-256 `5e7dd945ea30274d67cb9b9e8a07c175ca6c5bb6d199f2e890db5cccbd291583`. Empreintes concordantes avec SHA256SUMS, l’asset GitHub et le journal CI. Signature, version 30 / 0.9.1-wp-gemma-test, ABI ARM64, permission caméra et alignement ZIP/natif 16 Ko vérifiés sur ce téléchargement. Les **1 048 entrées internes** de l’APK publique sont identiques octet pour octet à celles de l’APK testée localement ; la représentation du conteneur diffère. Aucun appareil Android ni émulateur connecté : viseur, permissions HyperOS, presse-papier inter-applications et réception ChatGPT Remote non exécutés ici. Le modèle et les paramètres Gemma ne changent pas.

## Sources primaires consultées le 9 septembre 2026

- [Android : réception de contenu riche](https://developer.android.com/develop/ui/views/receive-rich-content).
- [Android : API image des claviers](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard).
- [Android : ClipData.Item et conversion vers du texte](https://developer.android.com/reference/android/content/ClipData.Item).
- [Android : aperçu Camera2, orientation et mise à l’échelle](https://developer.android.com/media/camera/camera2/camera-preview).
- [Chromium : lecture des URI d’images du presse-papier](https://chromium.googlesource.com/chromium/src/+/main/ui/android/java/src/org/chromium/ui/base/ClipboardImpl.java).
- [Chromium : ACTION_PASTE appelle le collage WebContents](https://chromium.googlesource.com/chromium/src/+/main/content/public/android/java/src/org/chromium/content/browser/accessibility/WebContentsAccessibilityImpl.java).

L’inspection de Chromium documente un mécanisme possible ; elle ne valide pas le champ de l’application native ChatGPT.
