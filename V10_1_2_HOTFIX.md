# v10.1.2 hotfix

Основа: production v10.1.0 + UI/UX-изменения v10.1.1.

v10.1.1 не был переключён в production: обязательный `mvn -B verify` остановил deployment на testCompile.
Причина: `getAuthorizationVersion()` возвращает `int`, а новый regression-test сохранял значение в `long`, после чего AssertJ `IntegerAssert.isGreaterThan(...)` не принимал `long`.

v10.1.2 меняет только тестовый тип `long` -> `int` и добавляет source-audit regression gate для этого случая. Production-код и UI/UX v10.1.1 не изменяются.
