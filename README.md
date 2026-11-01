# Bayti
Upload all files to a GitHub repository (including debug.keystore and the .github/workflows folder).
Actions builds Bayti.apk. Download the artifact named Bayti, unzip it, and install Bayti.apk.
Every build is signed with the same key (debug.keystore) so new versions install over old ones.
Firebase settings are already inside firebase-config.js.
