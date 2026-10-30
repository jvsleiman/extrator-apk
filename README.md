# Extrator de APKs

App Android (Kotlin + Jetpack Compose) que lista os apps instalados, deixa selecionar vários e extrai cada um para um arquivo instalável. Não precisa de root.

- **App comum** → `Nome_versão.apk`, que instala direto.
- **App dividido** (split APK, marcado "dividido" na lista) → `Nome_versão.apks`, um zip com `base.apk` + todos os `split_*.apk` (mais `icon.png` e `info.json`). Instale com o SAI (Split APKs Installer).
- Os dados do app e os arquivos OBB não são copiados.

Na primeira extração o app pede uma pasta de destino; dá para trocar no menu ⋮.

## Build

O GitHub Actions (`.github/workflows/build.yml`) compila a cada push em `main` e publica (no artifact do run e no branch `builds`, pasta `out/`) o APK **sem assinatura** (`ExtratorAPK-unsigned.apk`) e o `apksigner.jar` do Android SDK.

A keystore não fica no repositório. Para assinar:

```
java -jar apksigner.jar sign --ks extrator-release.jks --ks-key-alias extrator --out ExtratorAPK-android.apk ExtratorAPK-unsigned.apk
```

Localmente com o Android SDK: crie `keystore.properties` na raiz (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) e rode `./gradlew assembleRelease`; sai assinado em `app/build/outputs/apk/release/app-release.apk`.

Todas as atualizações precisam da mesma keystore (alias `extrator`), senão o Android não instala por cima.
