# Mini Ced — Phase 1 + début Phase 2 (prototype)

Ce projet est le squelette fonctionnel décrit dans le document d'architecture :
un avatar flottant en overlay + commande vocale, avec maintenant une vraie
connexion à l'API Claude (recherche web incluse), en plus du repli hors-ligne
si aucune clé API n'est configurée.

## Ce que fait déjà ce prototype

- Un bouton pour **configurer ta clé API Claude** (créée sur console.anthropic.com),
  stockée chiffrée sur l'appareil (jamais codée en dur, jamais en clair).
- Un bouton pour autoriser l'affichage par-dessus les autres apps.
- Un bouton pour faire apparaître Mini Ced : un petit rond bleu flottant,
  **déplaçable** (glisser) et qui **devient semi-transparent** si tu tapes dessus
  (simulateur simple de "visible / fondu").
- Un bouton "Parler à Mini Ced" qui ouvre la reconnaissance vocale Android,
  envoie ce que tu as dit à Claude (via `ClaudeClient.kt`), et fait répondre
  Mini Ced à voix haute.
- **Recherche web autonome** : l'outil `web_search` est activé côté API — Mini
  Ced décide lui-même de chercher sur le web quand ta question le demande
  (actualité, info précise, etc.), sans que tu aies à le formuler spécialement.
- **Honnêteté façon Jarvis** : le system prompt (dans `ClaudeClient.kt`) demande
  explicitement à Mini Ced de te reprendre si tu affirmes quelque chose de faux,
  plutôt que de te flatter par politesse. Tu peux ajuster le ton dans ce prompt.
- Si aucune clé API n'est configurée, `Brain.kt` prend le relais avec un message
  t'invitant à la configurer (mode dégradé, pas de vraie intelligence ni recherche).

## Comment obtenir l'APK sans Android Studio (via GitHub Actions)

Ce projet contient déjà tout le nécessaire (`.github/workflows/build.yml`) pour
que **GitHub compile l'APK à ta place**, gratuitement, dans le cloud.

1. Crée un compte sur [github.com](https://github.com) si tu n'en as pas.
2. Clique sur **"New repository"**, donne-lui un nom (ex: `mini-ced`), garde-le
   en **Private** si tu préfères, puis **"Create repository"**.
3. Sur la page du dépôt vide, clique sur **"uploading an existing file"**
   (ou "Add file" > "Upload files").
4. Dépose (glisser-déposer) **tout le contenu du dossier `MiniCed`** dézippé
   (pas le zip lui-même, son contenu) — ça doit inclure le dossier `.github`,
   `app`, `build.gradle.kts`, etc. Puis clique **"Commit changes"**.
5. Va dans l'onglet **"Actions"** en haut du dépôt : une compilation démarre
   automatiquement (icône jaune = en cours, ~5-10 minutes).
6. Une fois terminé (icône verte ✅), clique sur le run, puis tout en bas dans
   **"Artifacts"**, télécharge **`MiniCed-debug-apk`** (un .zip contenant l'APK).
7. Décompresse ce zip pour récupérer `app-debug.apk`, transfère-le sur ton
   téléphone (email, Drive, WhatsApp à toi-même...), ouvre-le et installe-le
   (Android demandera d'autoriser "Installer des apps inconnues" pour la
   première fois — normal pour un APK hors Play Store).

Chaque fois que tu modifieras le code (nouveau upload de fichiers sur GitHub),
une nouvelle compilation se relancera automatiquement.

## Comment l'ouvrir dans Android Studio (si tu as un ordinateur compatible)

1. Installer **Android Studio** (dernière version stable).
2. `File > Open`, sélectionner le dossier `MiniCed`.
3. Laisser Android Studio synchroniser le projet (il complètera automatiquement
   le wrapper Gradle si besoin).
4. Brancher un téléphone Android (mode développeur + débogage USB activés) ou
   utiliser un émulateur, puis `Run`.

## Prochaines étapes (fin de Phase 2)

1. **Ajouter la météo** :
   - Créer un `WorkManager` périodique qui interroge une API météo et déclenche
     une notification + une réaction de l'avatar en cas d'alerte.
3. **Ajouter les rappels/tâches** :
   - `AlarmManager` pour déclencher le rappel à l'heure prévue.
   - Stocker les tâches dans une base **Room** plutôt qu'en mémoire.
4. **Avatar animé** :
   - Décommenter la dépendance Lottie dans `app/build.gradle.kts`.
   - Déposer un fichier d'animation `.json` dans `res/raw/`.
   - Remplacer l'`ImageView` de `overlay_avatar.xml` par une `LottieAnimationView`,
     et piloter les états via `OverlayService.setState(...)`.
5. **Mot de réveil ("Hey Mini Ced")** :
   - Intégrer le SDK **Porcupine** (Picovoice) pour une écoute continue légère,
     à la place du déclenchement par bouton.

## Points d'attention

- La permission d'overlay (`SYSTEM_ALERT_WINDOW`) doit être activée manuellement
  par l'utilisateur dans les réglages système — c'est normal, Android l'exige.
- Pense à désactiver l'optimisation de batterie pour Mini Ced (réglages système)
  sinon le service overlay risque d'être arrêté par Android en arrière-plan.
- Pour un usage strictement personnel, installer l'APK directement (hors Play
  Store) évite les restrictions de validation liées à l'overlay + au micro.
- **Clé API** : crée-la sur console.anthropic.com. L'usage de l'API est facturé
  par Anthropic au volume de texte échangé (pas d'abonnement fixe) — surveille
  ta consommation sur la console si tu utilises Mini Ced souvent.
- La recherche web côté API a un coût légèrement supérieur à une simple
  question, puisque Claude doit interroger le web avant de répondre.
