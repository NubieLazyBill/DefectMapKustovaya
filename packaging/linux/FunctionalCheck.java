import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.defectmap.Database;
import org.example.defectmap.DefectData;
import org.example.defectmap.EquipmentData;
import org.example.defectmap.report.ExcelReportWriter;
import org.example.defectmap.report.ReportItem;
import org.example.defectmap.report.ReportPrinter;

/**
 * Функциональная проверка DefectMap без GUI:
 *   1) запись/чтение/удаление дефекта в реальной БД;
 *   2) экспорт отчёта в xlsx и обратное чтение;
 *   3) реальная отправка задания печати (Cups-PDF) и поиск PDF.
 *
 * Запуск (класс собран отдельно, jar — готовый fat JAR приложения):
 *   javac -cp build/libs/DefectMap.jar -d /tmp/fc /tmp/FunctionalCheck.java
 *   java -Ddefectmap.home=/home/admin/dm-data \
 *        -cp build/libs/DefectMap.jar:/tmp/fc FunctionalCheck
 */
public class FunctionalCheck {

    static int fails = 0;

    static void ok(String msg) { System.out.println("  [OK]   " + msg); }
    static void fail(String msg) { fails++; System.out.println("  [FAIL] " + msg); }
    static void info(String msg) { System.out.println("  " + msg); }
    static String line() { return "--------------------------------------------------------------"; }

    public static void main(String[] args) throws Exception {
        System.out.println("==============================================================");
        System.out.println("DefectMap - функциональная проверка БД / Excel / печати");
        System.out.println("==============================================================");
        System.out.println("  Каталог данных: " + System.getProperty("defectmap.home"));
        System.out.println("  Домашний каталог: " + System.getProperty("user.home"));

        List<ReportItem> items = checkDatabase();
        checkExcel(items);
        checkPrint(items);

        System.out.println(line());
        if (fails == 0) {
            System.out.println("РЕЗУЛЬТАТ: OK");
        } else {
            System.out.println("РЕЗУЛЬТАТ: FAIL (ошибок: " + fails + ")");
        }
        System.out.println(line());
        System.exit(fails == 0 ? 0 : 1);
    }

    // ======================== 1. База данных ========================

    static List<ReportItem> checkDatabase() {
        System.out.println();
        System.out.println("[1/3. База данных]");
        List<ReportItem> items = new ArrayList<>();
        Database db = null;
        try {
            db = new Database("kustovaya");
            int count = db.getCount();
            List<EquipmentData> all = db.loadAllEquipment();
            info("Оборудования прочитано: " + count + " (списком: " + all.size() + ")");
            if (all.isEmpty()) {
                fail("в БД нет оборудования");
                return items;
            }
            ok("БД открыта и читается: " + all.size() + " записей");

            EquipmentData eq = all.get(0);
            String defectId = "func-" + System.currentTimeMillis();
            DefectData defect = new DefectData(
                    defectId, eq.getId(), "FUNCTIONAL CHECK", "created by automated check",
                    "medium", "open", System.currentTimeMillis(), null, null, "", 50.5, 50.5);
            db.saveDefect(defect);

            boolean found = false;
            for (DefectData d : db.getDefectsByEquipment(eq.getId())) {
                if (d.getId().equals(defectId)) found = true;
            }
            if (found) ok("Дефект записан и прочитан обратно (оборудование: " + eq.getName() + ")");
            else fail("дефект не прочитан обратно");

            DefectData updated = new DefectData(
                    defectId, eq.getId(), "FUNCTIONAL CHECK", "updated by automated check",
                    "medium", "fixed", System.currentTimeMillis(), System.currentTimeMillis(), null, "", 50.5, 50.5);
            db.updateDefect(updated);
            String status = null;
            for (DefectData d : db.getDefectsByEquipment(eq.getId())) {
                if (d.getId().equals(defectId)) status = d.getStatus();
            }
            if ("fixed".equals(status)) ok("Дефект обновлён (статус fixed)");
            else fail("обновление дефекта не применилось");

            db.deleteDefect(defectId);
            boolean gone = true;
            for (DefectData d : db.getDefectsByEquipment(eq.getId())) {
                if (d.getId().equals(defectId)) gone = false;
            }
            if (gone) ok("Тестовый дефект удалён (данные не засорены)");
            else fail("тестовый дефект не удалился");

            int limit = Math.min(6, all.size());
            for (int i = 0; i < limit; i++) {
                EquipmentData e = all.get(i);
                Double left = null;
                Double top = null;
                if (!e.getMarkers().isEmpty()) {
                    left = e.getMarkers().get(0).getLeft();
                    top = e.getMarkers().get(0).getTop();
                }
                items.add(new ReportItem(
                        e.getName(), e.getType(), e.getCell(), "TEST DEFECT " + (i + 1),
                        "automated functional check", (i % 2 == 0) ? "Обнаружен" : "Устранён",
                        left, top, System.currentTimeMillis(), null, null));
            }
            info("Для отчёта подготовлено строк: " + items.size());
        } catch (Throwable t) {
            fail("ошибка работы с БД: " + t);
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) { }
            }
        }
        return items;
    }

    // ======================== 2. Excel ========================

    static void checkExcel(List<ReportItem> items) {
        System.out.println();
        System.out.println("[2/3. Экспорт Excel]");
        File out = new File(System.getProperty("user.home"), "defectmap-functional-report.xlsx");
        try {
            if (out.exists() && !out.delete()) {
                info("не удалось удалить старый отчёт, перезапишем");
            }
            ExcelReportWriter.INSTANCE.write(items, out);
            if (!out.isFile() || out.length() == 0) {
                fail("файл xlsx не создан");
                return;
            }
            ok("xlsx создан: " + out.getAbsolutePath() + " (" + out.length() + " bytes)");

            try (FileInputStream in = new FileInputStream(out);
                 XSSFWorkbook wb = new XSSFWorkbook(in)) {
                String sheetName = ExcelReportWriter.SHEET_NAME;
                if (wb.getSheet(sheetName) == null) {
                    fail("лист '" + sheetName + "' не найден");
                    return;
                }
                int rows = wb.getSheet(sheetName).getLastRowNum();
                info("Лист '" + sheetName + "', последняя строка: " + rows);
                if (rows >= items.size()) {
                    ok("xlsx читается обратно, строк данных: " + items.size());
                } else {
                    fail("в xlsx меньше строк, чем ожидалось");
                }
            }
        } catch (Throwable t) {
            fail("ошибка Excel: " + t);
        }
    }

    // ======================== 3. Печать ========================

    static void checkPrint(List<ReportItem> items) {
        System.out.println();
        System.out.println("[3/3. Печать (Cups-PDF)]");
        final File pdfDir = new File(System.getProperty("user.home"), "PDF");
        final long startedAt = System.currentTimeMillis();
        final int before = countPdf(pdfDir);
        info("PDF в " + pdfDir.getAbsolutePath() + " до печати: " + (before < 0 ? "каталога нет" : before));

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("не выполнялось");
        final boolean[] printed = {false};

        try {
            Platform.startup(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object outcome = ReportPrinter.INSTANCE.print(items, null, "DefectMap - functional check");
                        if (outcome instanceof ReportPrinter.PrintOutcome.Printed) {
                            ReportPrinter.PrintOutcome.Printed p = (ReportPrinter.PrintOutcome.Printed) outcome;
                            printed[0] = true;
                            result.set("отправлено на печать: " + p.getPages() + " стр., принтер «" + p.getPrinter() + "»");
                        } else if (outcome instanceof ReportPrinter.PrintOutcome.NoPrinters) {
                            result.set("в системе нет принтеров");
                        } else if (outcome instanceof ReportPrinter.PrintOutcome.Failed) {
                            result.set("ошибка печати: " + ((ReportPrinter.PrintOutcome.Failed) outcome).getMessage());
                        } else {
                            result.set("неожиданный результат: " + outcome);
                        }
                    } catch (Throwable t) {
                        result.set("исключение при печати: " + t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(120, TimeUnit.SECONDS);
        } catch (Throwable t) {
            result.set("не удалось запустить JavaFX: " + t);
        }

        if (printed[0]) ok(result.get());
        else fail(result.get());

        if (printed[0]) {
            // Cups-PDF может сохранить файл в ~/PDF или на рабочий стол
            File home = new File(System.getProperty("user.home"));
            File[] dirs = new File[] {
                pdfDir,
                new File(home, "PDF"),
                new File(home, "Desktop"),
                new File(home, "\u0420\u0430\u0431\u043e\u0447\u0438\u0439 \u0441\u0442\u043e\u043b")
            };
            File newest = null;
            for (int i = 0; i < 40; i++) {
                newest = newestPdfSince(dirs, startedAt);
                if (newest != null) break;
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
            if (newest != null) {
                ok("PDF создан: " + newest.getAbsolutePath() + " (" + newest.length() + " bytes)");
            } else {
                fail("задание отправлено, но PDF не найден за 20 с");
            }
        }

        try { Platform.exit(); } catch (Throwable ignored) { }
    }

    static int countPdf(File dir) {
        if (dir == null || !dir.isDirectory()) return -1;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) if (f.getName().toLowerCase().endsWith(".pdf")) n++;
        return n;
    }

    static File newestPdfSince(File[] dirs, long sinceMillis) {
        File best = null;
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.getName().toLowerCase().endsWith(".pdf")) continue;
                if (f.lastModified() + 1000 < sinceMillis) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best;
    }
}