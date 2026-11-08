ЛБС KML — Android-приложение

Что делает:
  Кнопка «Обновить ЛБС» загружает свежий снимок DeepStateMap,
  строит линию фронта и сохраняет LBS_ДД_ММ_ГГГГ.kml в папку «Загрузки».
  «Открыть» — открыть в Google Earth, «Поделиться» — отправить файл.
  Требуется Android 10 или новее.

Как собрать в Android Studio:
  1. Распакуйте архив, в Android Studio: File > Open > папка LbsKml.
  2. Дождитесь окончания синхронизации Gradle (первый раз скачивает
     компоненты, нужен интернет; если Studio предложит обновить
     Android Gradle Plugin или установить SDK 35 — соглашайтесь).
  3. Build > Build App Bundle(s) / APK(s) > Build APK(s).
  4. Готовый файл: app/build/outputs/apk/debug/app-debug.apk
     Скиньте его на телефон и установите (разрешите установку
     из неизвестных источников).
  Либо подключите телефон по USB с включённой отладкой и нажмите Run.

Сборка через GitHub (без Android Studio):
  Загрузите содержимое папки LbsKml в репозиторий GitHub (вместе с
  папкой .github). GitHub Actions соберёт APK автоматически за ~5 минут.
  Готовый файл: вкладка Releases > latest > LbsKml.apk
  (ссылка для телефона: https://github.com/<логин>/<репозиторий>/releases/latest)
