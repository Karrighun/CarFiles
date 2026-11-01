# CarFiles Lab · эксперимент Android Auto

## Реализация

Сборка `-PprojectionLab=true` добавляет `app/src/projectionLab`, локальную зависимость `app/libs/aauto.aar`, AppCompat и Fragment. Package suffix `.lab` позволяет установить её рядом с обычной версией.

`CarProjectionService` регистрируется как legacy projection service. `CarProjectionActivity` показывает меню, WebView YouTube либо ImageView, обновляемый кадрами MediaProjection. Захват запускается пользователем на телефоне через системный диалог. Остановка/уход с автомобильного экрана останавливают обновление поверхности.

Кадры для адаптера сейчас передаются через `ProjectionState.latestJpeg` в памяти приложения. Если host запускает этот компонент в другом процессе, потребуется IPC через Binder/Surface или локальный поток. Это ещё не проверено.

## Происхождение SDK

Бинарная зависимость взята из публичного репозитория Fermata:

https://raw.githubusercontent.com/AndreyPavlenko/Fermata/master/fermata/lib/auto/aauto.aar

SHA-256: `99337c3b591ac9670c12b508da38886aedba61dd494f39f5f166f02580ec584b`.

Исследованы API CarActivity/CarActivityService и manifest Fermata; исходный код экранов Fermata в CarFiles не копировался. Библиотека является неофициальным старым SDK, не актуальным публичным API Google. В README проекта https://github.com/martoreto/aauto-sdk указано, что автор снял SDK с публикации по запросу Google. Поддержка и условия распространения бинарного компонента требуют отдельной проверки до публичного релиза. Эта зависимость включена только в явно выбранный Lab-вариант.

## Уровень проверки

- APK скомпилирован, упакован, подписан; 5 unit-тестов и lint прошли.
- APK установлен в Android 15 ARM64, главный экран запускается без AndroidRuntime crash.
- Автомобильный компонент Android Auto host ещё не запускал.
- Видимость в лаунчере Android Auto, YouTube на магнитоле, зеркалирование на магнитоле и совместимость Samsung S24 FE/GAC GS8 2026 пока не подтверждены.

Эмулятор телефона не является DHU, а запуск MainActivity не является запуском CarActivity. Полученный Lab APK предназначен для следующего технического эксперимента.
