# המחותן — הנחיות פיתוח

אפליקציית Android (Kotlin) לניהול חתונה, לציבור החרדי. עברית ו-RTL מלאים, עובדת ללא אינטרנט.

## פקודות
- התקנת כלי בנייה (פעם אחת, Ubuntu): `./tools/setup-toolchain.sh` (ברירת מחדל `/opt/tc`, או `TOOLCHAIN=...`)
- בדיקות: `./tools/run-tests.sh` — חובה שיעברו לפני כל push
- בנייה: `./build.sh` → `build/outputs/HaMechutan-<version>.apk`

## כללי ארכיטקטורה
- `core/` הוא Kotlin טהור, **בלי** `import android.*`. כל הלוגיקה העסקית ב-`Repo`, עם בדיקות JVM מול SQLite.
- כסף באגורות (Long). "שולם" ו"יתרה" מחושבים תמיד מטבלת התשלומים, לעולם לא נשמרים פעמיים.
- הממשק נבנה בקוד (`ui/Ui.kt`, `ui/screens/*`), בלי AndroidX, Compose או Material Components.
- אין להוסיף הרשאת INTERNET.

## פרסום גרסה
1. מעלים את `versionCode` ומעדכנים את `versionName` ב-`version.properties`.
2. מוסיפים סעיף לגרסה בראש `CHANGELOG.md`.
3. דוחפים ל-`main`. GitHub Actions בונה, בודק, חותם ויוצר Release בשם `v<version>`.
- מפתח החתימה **לעולם** לא נכנס למאגר. הוא שמור ב-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`.

## תוכן
- קהל חרדי: אין תמונות או איורים של אנשים; העיצוב צנוע ומכובד. הפנייה למשתמש בלשון רבים ("הוסיפו").
