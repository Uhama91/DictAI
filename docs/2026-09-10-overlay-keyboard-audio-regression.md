# Régression overlay, clavier et démarrage audio

Checklist de validation manuelle pour l’APK construit avec le correctif :

1. Pendant une dictée, ouvrir **Modifier** et agrandir le panneau avec Gboard visible. Vérifier que le bord inférieur du panneau reste au-dessus du clavier et que les derniers mots restent visibles. Fermer puis rouvrir Gboard en pause et répéter en paysage ; déplacer la pastille sur chacun des quatre bords pour vérifier le placement.
2. Toucher un mot au début du texte, le corriger, puis laisser la dictée produire plusieurs lignes. Vérifier que le curseur reste sur le mot corrigé pendant la saisie et que la vue rejoint la fin après le délai d’inactivité. Toucher ensuite un mot visible en bas : le curseur doit se placer sous le doigt.
3. Répéter en gardant une composition Gboard active puis en cessant de taper. Vérifier qu’une composition résiduelle ne bloque pas indéfiniment le suivi de la fin.
4. Démarrer une dictée en parlant dès l’appui. Comparer les journaux `audio_reader_start` et `audio_first_frame` (`startup_ms`, `peak`) ; vérifier que le début du texte n’est pas systématiquement absent. Répéter après une pause/reprise et avec le routage audio utilisé habituellement.

La mitigation audio réduit le temps entre `AudioRecord.startRecording()` et la lecture du premier bloc en lançant le lecteur avant la persistance et le rendu de l’overlay. Les métriques journalisées objectivent le délai et le niveau du premier bloc ; elles ne constituent pas une mesure de taux d’erreur du modèle.
