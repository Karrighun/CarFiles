# Аналоги и технические выводы · 31.10.2026

Исследованы публичные страницы Google Play, README, исходные манифесты и классы. Приложения не установлены и на магнитоле не проверены. Наличие страницы Google Play не подтверждает доступность для конкретного аккаунта/страны/автомобиля.

| Приложение | Что подтверждено первичным источником | Что полезно CarFiles |
|---|---|---|
| Fermata | Открытое ядро, папки, плейлисты, позиции по папкам, сменные движки; отдельный auto flavor | Основной источник архитектурных идей для видео и автомобильной интеграции |
| CarStream | Открытый проект YouTube/Plex/локального видео; в manifest два пути — projection service и media browser | Видеоинтерфейс и аудиосеанс должны быть независимыми адаптерами |
| Musicolet, Google Play | Локальное аудио; папки, очереди и библиотека в Android Auto | Ориентир функционального MVP: очередь по папке и сохранение позиции |
| VLC, Google Play | Локальные и сетевые файлы, папки, собственные кодеки, субтитры | Резервный движок нужен только при подтверждённых неподдерживаемых кодеках; расширение файла недостаточно |
| KuroMediaPlayer, Google Play | Android-магнитолы/планшеты, локальные папки, OneDrive, Drive через системный выбор | «Для автомобиля» не обязательно означает Android Auto; отдельно различать локальный Android и проекцию |
| AA Car Play Video | Закрытый продукт, публичная документация и сайт | Референс поведения, но не подтверждение доступного SDK |

## Конкретная находка в Fermata

`fermata/src/auto/java/me/aap/fermata/auto/CarService.java` наследует `com.google.android.apps.auto.sdk.CarActivityService` и возвращает `MainCarActivity`. Последний наследует `CarActivity`, управляет автомобильной оболочкой и делегирует работу общему UI.

В `fermata/build.gradle` отдельные flavors `mobile` и `auto`; для auto подключаются локальный `lib/auto/aauto.aar`, Xposed API как compileOnly и Car App Library. В auto-манифесте есть сервисы с `CATEGORY_PROJECTION`/`CATEGORY_PROJECTION_OEM`, отдельный путь зеркалирования через navigation service, MediaProjection и Accessibility. Само наличие этих механизмов не означает, что для всех функций обязательно нужен root.

Вывод: стандартный MediaLibraryService не является механизмом видео Fermata. Есть конкретный альтернативный путь для исследовательского прототипа, который нужно оценить на нужной версии Android Auto. Это также не доказывает, что AA Car Play Video использует тот же код или API.

Следующий эксперимент: отдельный модуль `projection-lab`, минимальный статический экран, затем тестовый Surface плеера; проверка версии SDK, возможности законного распространения зависимости, появления сервиса и жизненного цикла. Не смешивать эксперимент с основным аудиосервисом до воспроизводимого запуска. В версии 0.2.0 реализован opt-in Lab адаптер; см. PROJECTION-LAB.md. Он собран и установлен, но пока не вызван Android Auto host.

## Почему Google Play не даёт ответа автоматически

Musicolet прямо заявляет Android Auto для аудио. KuroMediaPlayer заявляет автомобильные Android-устройства, что не подтверждает поддержку Android Auto. Страница VLC подтверждает возможности проигрывателя, но сама по себе не доказывает вывод видео в Android Auto. Страницу Fermata с предполагаемым package ID открыть не удалось — наличие текущей публичной карточки не подтверждено.

## Изменения в нашем проекте по итогам исследования

1. Сохраняем независимое файловое ядро и штатный аудиосервис.
2. Вывод видео на машину исследуем отдельным адаптером проекции.
3. Устранили возврат в корень при переходе по вложенным SAF URI: запрос идёт по фактическому document ID. Список читается одним запросом DocumentsProvider.
4. Первым проверяем сборку, затем запуск на Android, после этого DHU и реальное головное устройство. Это три разных уровня проверки.

## Источники

- https://github.com/AndreyPavlenko/Fermata
- https://raw.githubusercontent.com/AndreyPavlenko/Fermata/master/fermata/src/auto/java/me/aap/fermata/auto/CarService.java
- https://raw.githubusercontent.com/AndreyPavlenko/Fermata/master/fermata/src/auto/java/me/aap/fermata/auto/MainCarActivity.java
- https://raw.githubusercontent.com/AndreyPavlenko/Fermata/master/fermata/src/auto/AndroidManifest.xml
- https://raw.githubusercontent.com/AndreyPavlenko/Fermata/master/fermata/build.gradle
- https://github.com/thekirankumar/carstream-android-auto
- https://raw.githubusercontent.com/thekirankumar/carstream-android-auto/master/app/src/main/AndroidManifest.xml
- https://play.google.com/store/apps/details?id=in.krosbits.musicolet
- https://play.google.com/store/apps/details?id=org.videolan.vlc
- https://play.google.com/store/apps/details?id=com.ishichan.carmediaplayer
- https://github.com/aacarplayvideo/aacarplayvideo
- https://aacarplayvideo.com/
