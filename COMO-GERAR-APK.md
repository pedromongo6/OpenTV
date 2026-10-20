# OpenTV — gerar o APK instalável

Este projeto **ainda não tem um APK pronto**. Ele tem o código completo; falta compilar.
Compilar exige JDK 17 + Android SDK, que não existem nesta máquina e não podem ser
baixados daqui (o sandbox bloqueia handshake TLS).

Há três caminhos. O primeiro é o mais rápido e não instala nada no seu PC.

---

## Opção 1 — GitHub Actions (recomendado, ~5 minutos)

O workflow já está em `.github/workflows/build-apk.yml`. Ele compila na nuvem do GitHub
e te entrega o APK para baixar.

1. Crie um repositório **vazio** em https://github.com/new
   - Nome sugerido: `opentv`
   - **Não** marque "Add a README"
   - Pode ser **privado** — o Actions funciona igual

2. Abra o PowerShell na pasta `OpenTV` e rode os comandos abaixo, trocando
   `SEU-USUARIO` e `opentv` pelos seus dados:

```powershell
cd "C:\Users\pedro\Downloads\REA\OpenTV"

git init
git add .
git commit -m "OpenTV 1.0.0 - cliente IPTV completo"
git branch -M main
git remote add origin https://github.com/SEU-USUARIO/opentv.git
git push -u origin main
```

Se pedir login, use um **Personal Access Token** como senha:
https://github.com/settings/tokens → Generate new token (classic) → marque `repo`.

3. Abra `https://github.com/SEU-USUARIO/opentv/actions`
   - O build começa sozinho. Leva ~4–6 min.
   - Entre no run verde mais recente.

4. Em **Artifacts**, baixe **`OpenTV-APK`**. Vem um `.zip` com
   `OpenTV-1.0.0-debug.apk` dentro.

5. Mande esse APK para o celular (Drive, Telegram, cabo, e-mail) e instale.
   Android vai pedir para permitir "instalar de fontes desconhecidas" — é esperado,
   o APK é assinado com chave de debug, não com chave de loja.

---

## Opção 2 — Compilar no seu PC

Precisa de ~6 GB livres e uma internet que funcione no PC (a do celular serve).

1. **JDK 17** — https://adoptium.net/temurin/releases/?version=17
   Baixe o `.msi` do Windows x64, instale, marque "Set JAVA_HOME".

2. **Android Studio** — https://developer.android.com/studio
   Instala o SDK junto. Na primeira abertura, deixe o setup padrão rodar
   (baixa o SDK 34 sozinho).

3. Feche o Android Studio. No PowerShell:

```powershell
cd "C:\Users\pedro\Downloads\REA\OpenTV"

# aponta para o SDK (ajuste o caminho se instalou em outro lugar)
"C:\Users\pedro\AppData\Local\Android\Sdk" | Out-File -Encoding ascii local.properties -Append
# o arquivo precisa ficar exatamente:  sdk.dir=C\:\\Users\\pedro\\AppData\\Local\\Android\\Sdk
```

Mais simples: deixe o próprio Android Studio abrir a pasta `OpenTV`
(**File → Open** → selecione a pasta). Ele gera o `local.properties` e o wrapper
sozinho, e você usa **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

4. Instalar no celular por cabo (com depuração USB ligada):

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Opção 3 — Build sem Android Studio (linha de comando)

Só o JDK 17 e o SDK de linha de comando:

```powershell
# 1. JDK 17 instalado (veja Opção 2, passo 1)

# 2. Command-line tools do SDK
#    https://developer.android.com/studio#command-line-tools-only
#    Baixe "commandlinetools-win-*.zip" e extraia em C:\Android\Sdk\cmdline-tools\latest

# 3. Instale os pacotes
$env:ANDROID_HOME="C:\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 4. Gradle 8.9 (baixe o bin do site oficial e extraia)
#    https://gradle.org/releases/  ->  gradle-8.9-bin.zip

# 5. Gere o wrapper e compile
cd "C:\Users\pedro\Downloads\REA\OpenTV"
"$env:ANDROID_HOME" | ForEach-Object { "sdk.dir=" + ($_ -replace '\\','\\') } | Set-Content local.properties
C:\gradle-8.9\bin\gradle.bat assembleDebug
```

APK em `app\build\outputs\apk\debug\app-debug.apk`.

---

## Depois de instalar

Na primeira abertura o app pede uma fonte. Duas opções:

**Playlist M3U (mais fácil)** — toque em **M3U playlist** e cole uma URL que termine
em `.m3u` ou `.m3u8`. Para testar rápido, use uma lista pública gratuita, tipo as de
https://github.com/iptv-org/iptv (procure por `index.m3u` do Brasil).
O campo de EPG é opcional: cole um XMLTV para ter a grade de programação.

**Painel Xtream** — toque em **Xtream Codes panel** e preencha host, usuário e senha
de um painel que você tenha acesso.

Se quiser também um servidor próprio com seus arquivos, o cliente fala com
Jellyfin/Emby via playlist M3U.

---

## O que está pronto e o que não está

| | |
|---|---|
| Código | 26 arquivos Kotlin, 3.549 linhas, 24 layouts — completo, sem stubs |
| Lógica do parser M3U | **testada e passando** (12/12), inclusive um bug de `#EXTGRP` corrigido |
| Referências de recurso/view binding | cruzadas e conferidas |
| Compilação do APK | **não executada** — sem toolchain nesta máquina |
| Instalação em aparelho | **não testada** |

Ou seja: o build deve passar, mas eu não vou dizer que passou sem ter rodado.
Se der erro em algum passo, me mande a saída que eu corrijo.
