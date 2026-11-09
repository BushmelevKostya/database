#!/bin/sh
# Интерактивная консоль БД. Запускается напрямую через java, а не через `gradlew run`:
# Gradle подменяет ввод своим каналом, и стрелки (история команд) тогда не работают.
# Использование: ./shell.sh [каталог_данных]
cd "$(dirname "$0")" || exit 1
./gradlew -q installDist || exit 1
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "build/install/database/lib/*" org.itmo.ShellKt "$@"
