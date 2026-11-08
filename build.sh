#!/bin/bash
set -e
cd "$(dirname "$0")"

# 工具链位置：可以用环境变量覆盖，默认从 ANDROID_HOME 推导（GitHub Actions 走这条）
ANDROID_HOME="${ANDROID_HOME:-/opt/android}"
AJ="${AJ:-$ANDROID_HOME/platforms/android-34/android.jar}"
D8JAR="${D8JAR:-$(ls "$ANDROID_HOME"/build-tools/*/lib/d8.jar 2>/dev/null | sort -V | tail -1)}"
BT="$(dirname "$(dirname "$D8JAR")")"
AAPT="$BT/aapt"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

for f in "$AJ" "$D8JAR" "$AAPT" "$ZIPALIGN" "$APKSIGNER"; do
  [ -e "$f" ] || { echo "missing toolchain file: $f" >&2; exit 1; }
done

rm -rf build && mkdir -p build/classes build/gen build/dex

# 0. regenerate pet.html (animation list + work-status mapping)
#    需要 dsh-pet 的原始 webm 素材；素材不在时跳过（仓库里已提交生成好的 pet.html + webm/）
if [ -z "$SKIP_GEN_HTML" ] && [ -d "${DSH_PET_ASSETS:-/root/.dsh/profiles/web/node_modules/dsh-pet/assets/webm}" ]; then
  python3 scripts/gen_html.py
else
  echo "skip gen_html.py (素材不存在或 SKIP_GEN_HTML 已设置)"
fi

# 1. resources + assets -> base.apk, and R.java
"$AAPT" package -f -m -J build/gen -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res -I "$AJ" --min-sdk-version 26 --target-sdk-version 34
"$AAPT" package -f -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res -I "$AJ" -A app/src/main/assets \
  --min-sdk-version 26 --target-sdk-version 34 -F build/base.apk

# 2. java -> class -> dex
javac -encoding UTF-8 -source 1.8 -target 1.8 -classpath "$AJ" \
  -d build/classes app/src/main/java/com/dsha/pet/*.java build/gen/com/dsha/pet/R.java
java -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJ" --output build/dex build/classes/com/dsha/pet/*.class

# 3. dex into apk, align, sign
# 签名证书放在仓库根目录、跨次构建复用：否则每次重建都换钥匙，
# 已装机的旧版本会因为签名不同而无法覆盖安装（只能卸载重装、丢存档）。
# CI 里由 workflow 从 secrets 还原到 signing/debug.keystore。
KS="${KS:-$PWD/signing/debug.keystore}"
case "$KS" in /*) ;; *) KS="$PWD/$KS" ;; esac
mkdir -p "$(dirname "$KS")"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias debug \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
  -dname "CN=Pet, OU=Dev, O=DSHA, L=CN, ST=CN, C=CN" >/dev/null 2>&1
cd build
cp base.apk unsigned.apk
(cd dex && zip -0 -u ../unsigned.apk classes.dex >/dev/null)
"$ZIPALIGN" -f 4 unsigned.apk aligned.apk
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out pet.apk aligned.apk
"$APKSIGNER" verify pet.apk
ls -la pet.apk
