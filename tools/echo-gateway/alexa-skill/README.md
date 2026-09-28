# Kainos Test — Alexa skill (Phase 4, France)

Skill de développement privé : lance le flux de test de la gateway sur un Echo
lié à un compte **Amazon.fr** (pas un portail EU générique).

## Prérequis

1. Gateway + Funnel actifs (`../scripts/funnel-up.sh`).
2. Compte développeur Alexa sur le **même** compte Amazon.fr que l’Echo.
3. Auth ASK CLI via **www.amazon.fr** (pas amazon.com / amazon.de).

## Création

Après auth ASK (`ask configure --no-browser` avec l’URL amazon.fr), déployer
depuis ce dossier. Locale : **fr-FR**, distribution : **FR**.

Phrase de test :

> Alexa, ouvre Kainos test

Endpoint : `https://theadenkingof.taila3d69a.ts.net/alexa`
