# Contrôle du véhicule et réglage à l'OBD

Méthode de réglage d'un boîtier Bioflex STD (injection indirecte), résumée à partir de la
documentation utilisateur Biomotors et de l'observation de l'appli officielle. Les documents
de l'éditeur font foi : [guide utilisateur Biomotors](https://www.biomotors.fr/wp-content/uploads/2026/05/Guide-utilisateur-BIOMOTORS-final-1.pdf).

## 1. État du véhicule

Un réglage n'a de sens que sur un moteur sain :

- aucun code défaut permanent à l'OBD ;
- sondes lambda (O2) fonctionnelles ;
- niveaux d'huile et de liquide de refroidissement corrects ;
- aucune prise d'air, aucune fuite d'échappement avant le catalyseur ;
- bougies et filtre à air récents (moins de 15 000 km) ;
- révision à jour.

## 2. Relevé de référence

Moteur chaud (~85 °C), sondes lambda qui oscillent (boucle fermée), relever à l'OBD les
corrections de mélange **court terme** (STFT) et **long terme** (LTFT). Ce sont les valeurs
vers lesquelles le réglage doit ramener le moteur une fois à l'E85.

## 3. Réglage de l'enrichissement à chaud

Sur un boîtier STD, l'enrichissement à chaud suit une courbe selon le taux d'éthanol
(≈ 28 % à 65 % d'éthanol, 30 % à 70 %, 37 % à 85 %). Le réglage décale cette courbe, par
paliers de 5 %.

1. Additionner **STFT + LTFT** : c'est la correction globale demandée par le calculateur.
2. Corriger l'enrichissement à chaud du même ordre, au palier de 5 % le plus proche.
3. Rouler, relire, recommencer si besoin — un seul réglage à la fois.

Exemple : −0,8 % + 6,2 % = +5,4 % → **+5 %**. Somme positive : le calculateur ajoute du
carburant, le boîtier est trop pauvre. Somme négative : il est trop riche.

Sur Renault / Dacia / Nissan, multiplier la valeur lue par **0,4** (« +22 » → +8,8 %).

L'enrichissement à chaud se règle moteur tournant, OBD branché, pour voir l'effet en direct.

## 4. Réglage du départ à froid

Seuil, enrichissement à froid et consigne se règlent **moteur coupé, contact mis**. Le rôle
de chacun et le choix entre consigne 1 et consigne 2 sont décrits dans
[COMMANDES.md](COMMANDES.md#consigne-de-démarrage-m--1-ou-2).

## 5. Valeurs à noter après réglage

- enrichissement à froid et à chaud ;
- consigne et seuil de départ à froid ;
- taux d'éthanol mesuré ;
- corrections STFT / LTFT obtenues.

## Boîtiers GDI

Les boîtiers GDI (injection directe) disposent d'un **auto-apprentissage** lancé par le mode
« Auto » : voir [COMMANDES.md](COMMANDES.md#auto-apprentissage-gdi).
