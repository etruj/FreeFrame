package local.photoframe;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * Turns an iCalendar feed (Cozi's) into the next few days of events.
 *
 * Handles what calendar apps actually publish: all-day and timed events,
 * time zones, EXDATE, edited instances (RECURRENCE-ID) and RRULEs with
 * FREQ, INTERVAL, COUNT, UNTIL, BYDAY (with ordinals), BYMONTHDAY, BYMONTH
 * and BYSETPOS. Days are counted as days since 1970-01-01 ("epoch days").
 */
final class Ical {
    private static final String TAG = "FrameIcal";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private Ical() {
    }

    // ------------------------------------------------------------ dates

    static int epochDay(int y, int m, int d) {
        // Howard Hinnant's days_from_civil.
        y -= m <= 2 ? 1 : 0;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** {year, month, day} for an epoch day. */
    static int[] civil(int z) {
        z += 719468;
        int era = (z >= 0 ? z : z - 146096) / 146097;
        int doe = z - era * 146097;
        int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        int mp = (5 * doy + 2) / 153;
        int d = doy - (153 * mp + 2) / 5 + 1;
        int m = mp + (mp < 10 ? 3 : -9);
        return new int[] {yoe + era * 400 + (m <= 2 ? 1 : 0), m, d};
    }

    /** Non-negative remainder (Math.floorMod is missing before Android 7). */
    static int mod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    /** 0 = Sunday ... 6 = Saturday. */
    static int weekday(int day) {
        return mod(day + 4, 7);
    }

    static int daysInMonth(int y, int m) {
        return epochDay(m == 12 ? y + 1 : y, m == 12 ? 1 : m + 1, 1) - epochDay(y, m, 1);
    }

    static String isoDay(int day) {
        int[] c = civil(day);
        return String.format(Locale.US, "%04d-%02d-%02d", c[0], c[1], c[2]);
    }

    /** A DTSTART/DTEND/EXDATE value: a date, or a wall-clock time in a zone. */
    static final class When {
        int y, mo, d, h, mi, s;
        boolean dateOnly;
        TimeZone tz;

        int day() {
            return epochDay(y, mo, d);
        }

        /** This wall-clock time on another day, as an instant. */
        long msOn(int day) {
            int[] c = civil(day);
            Calendar cal = Calendar.getInstance(tz);
            cal.clear();
            cal.set(c[0], c[1] - 1, c[2], h, mi, s);
            return cal.getTimeInMillis();
        }

        long ms() {
            return msOn(day());
        }
    }

    static When parseWhen(Map<String, String> params, String v, TimeZone local) {
        v = v.trim();
        When w = new When();
        w.y = Integer.parseInt(v.substring(0, 4));
        w.mo = Integer.parseInt(v.substring(4, 6));
        w.d = Integer.parseInt(v.substring(6, 8));
        if (v.length() == 8) {
            w.dateOnly = true;
            w.tz = local;
            return w;
        }
        if (v.charAt(8) != 'T' || v.length() < 15) {
            throw new IllegalArgumentException("bad date " + v);
        }
        w.h = Integer.parseInt(v.substring(9, 11));
        w.mi = Integer.parseInt(v.substring(11, 13));
        w.s = Integer.parseInt(v.substring(13, 15));
        if (v.endsWith("Z")) {
            w.tz = UTC;
        } else {
            String id = params.get("TZID");
            TimeZone tz = id == null ? null : TimeZone.getTimeZone(id);
            // Unknown ids come back as GMT; fall back to the tablet's zone.
            w.tz = tz != null && tz.getID().equals(id) ? tz : local;
        }
        return w;
    }

    // ------------------------------------------------------------ parsing

    static final class Event {
        String uid = "";
        String summary = "";
        String location = "";
        When start;
        When end;
        String rrule;
        final List<When> exdates = new ArrayList<When>();
        When recurrenceId;
    }

    static List<String> unfold(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : text.split("\r?\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + line.substring(1));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    static List<Event> parse(String text, TimeZone local) {
        List<Event> events = new ArrayList<Event>();
        Event cur = null;
        for (String line : unfold(text)) {
            if (line.equals("BEGIN:VEVENT")) {
                cur = new Event();
                continue;
            }
            if (line.equals("END:VEVENT")) {
                if (cur != null && cur.start != null) {
                    events.add(cur);
                }
                cur = null;
                continue;
            }
            int colon = line.indexOf(':');
            if (cur == null || colon < 0) {
                continue;
            }
            String[] head = line.substring(0, colon).split(";");
            String value = line.substring(colon + 1);
            String name = head[0].toUpperCase(Locale.US);
            Map<String, String> params = new HashMap<String, String>();
            for (int i = 1; i < head.length; i++) {
                int eq = head[i].indexOf('=');
                if (eq > 0) {
                    params.put(head[i].substring(0, eq).toUpperCase(Locale.US),
                            head[i].substring(eq + 1).replace("\"", ""));
                }
            }
            try {
                if (name.equals("DTSTART")) {
                    cur.start = parseWhen(params, value, local);
                } else if (name.equals("DTEND")) {
                    cur.end = parseWhen(params, value, local);
                } else if (name.equals("EXDATE")) {
                    for (String v : value.split(",")) {
                        cur.exdates.add(parseWhen(params, v, local));
                    }
                } else if (name.equals("RECURRENCE-ID")) {
                    cur.recurrenceId = parseWhen(params, value, local);
                } else if (name.equals("RRULE")) {
                    cur.rrule = value;
                } else if (name.equals("SUMMARY")) {
                    cur.summary = unescape(value);
                } else if (name.equals("LOCATION")) {
                    cur.location = unescape(value);
                } else if (name.equals("UID")) {
                    cur.uid = value.trim();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "skipping " + name + ": " + e);
            }
        }
        return events;
    }

    // ------------------------------------------------------------ rules

    static final class Rule {
        String freq;
        int interval = 1;
        int count;
        When until;
        final List<int[]> byDay = new ArrayList<int[]>(); // {ordinal or 0, weekday}
        final List<Integer> byMonthDay = new ArrayList<Integer>();
        final List<Integer> byMonth = new ArrayList<Integer>();
        final List<Integer> bySetPos = new ArrayList<Integer>();
        int weekStart = 1; // Monday

        static final String DAYS = "SUMOTUWETHFRSA";

        /** Null when the rule uses parts this class does not understand. */
        static Rule parse(String text, TimeZone local) {
            Rule r = new Rule();
            for (String part : text.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = part.substring(0, eq).toUpperCase(Locale.US);
                String v = part.substring(eq + 1).toUpperCase(Locale.US);
                if (k.equals("FREQ")) {
                    r.freq = v;
                } else if (k.equals("INTERVAL")) {
                    r.interval = Math.max(1, Integer.parseInt(v));
                } else if (k.equals("COUNT")) {
                    r.count = Integer.parseInt(v);
                } else if (k.equals("UNTIL")) {
                    r.until = parseWhen(new HashMap<String, String>(), v, local);
                } else if (k.equals("WKST")) {
                    r.weekStart = DAYS.indexOf(v) / 2;
                } else if (k.equals("BYDAY")) {
                    for (String d : v.split(",")) {
                        String code = d.substring(d.length() - 2);
                        String ord = d.substring(0, d.length() - 2);
                        int wd = DAYS.indexOf(code);
                        if (wd < 0 || wd % 2 != 0) {
                            return null;
                        }
                        r.byDay.add(new int[] {
                                ord.isEmpty() || ord.equals("+") ? 0 : Integer.parseInt(ord.replace("+", "")),
                                wd / 2});
                    }
                } else if (k.equals("BYMONTHDAY")) {
                    addInts(r.byMonthDay, v);
                } else if (k.equals("BYMONTH")) {
                    addInts(r.byMonth, v);
                } else if (k.equals("BYSETPOS")) {
                    addInts(r.bySetPos, v);
                } else {
                    return null; // BYYEARDAY, BYWEEKNO, BYHOUR, ...
                }
            }
            if (!"DAILY".equals(r.freq) && !"WEEKLY".equals(r.freq)
                    && !"MONTHLY".equals(r.freq) && !"YEARLY".equals(r.freq)) {
                return null;
            }
            Collections.sort(r.byMonth);
            return r;
        }

        private static void addInts(List<Integer> list, String v) {
            for (String s : v.split(",")) {
                list.add(Integer.parseInt(s.replace("+", "")));
            }
        }

        boolean dayMatches(int day) {
            if (!byDay.isEmpty()) {
                boolean hit = false;
                for (int[] bd : byDay) {
                    hit |= bd[1] == weekday(day);
                }
                if (!hit) {
                    return false;
                }
            }
            int[] c = civil(day);
            if (!byMonth.isEmpty() && !byMonth.contains(c[1])) {
                return false;
            }
            if (!byMonthDay.isEmpty()) {
                int len = daysInMonth(c[0], c[1]);
                boolean hit = false;
                for (int v : byMonthDay) {
                    hit |= (v > 0 ? v : len + 1 + v) == c[2];
                }
                return hit;
            }
            return true;
        }

        /** Matching days of one month (BYMONTHDAY / BYDAY), sorted. */
        TreeSet<Integer> monthDays(int y, int m, int startDom) {
            TreeSet<Integer> out = new TreeSet<Integer>();
            int len = daysInMonth(y, m);
            int first = epochDay(y, m, 1);
            TreeSet<Integer> byDom = null;
            if (!byMonthDay.isEmpty()) {
                byDom = new TreeSet<Integer>();
                for (int v : byMonthDay) {
                    int dom = v > 0 ? v : len + 1 + v;
                    if (dom >= 1 && dom <= len) {
                        byDom.add(first + dom - 1);
                    }
                }
            }
            if (!byDay.isEmpty()) {
                for (int[] bd : byDay) {
                    List<Integer> all = new ArrayList<Integer>();
                    for (int d = first + mod(bd[1] - weekday(first), 7); d < first + len; d += 7) {
                        all.add(d);
                    }
                    pick(out, all, bd[0]);
                }
                if (byDom != null) {
                    out.retainAll(byDom);
                }
            } else if (byDom != null) {
                out.addAll(byDom);
            } else if (startDom <= len) {
                out.add(first + startDom - 1);
            }
            return out;
        }

        /** Candidate days of the period-th period after the start. */
        List<Integer> candidates(int start, int period) {
            int[] sc = civil(start);
            TreeSet<Integer> days = new TreeSet<Integer>();
            if (freq.equals("DAILY")) {
                int d = start + period * interval;
                if (dayMatches(d)) {
                    days.add(d);
                }
            } else if (freq.equals("WEEKLY")) {
                int weekFirst = start - mod(weekday(start) - weekStart, 7) + period * interval * 7;
                for (int d = weekFirst; d < weekFirst + 7; d++) {
                    boolean wd = byDay.isEmpty() ? weekday(d) == weekday(start) : dayMatches(d);
                    int m = civil(d)[1];
                    if (wd && (byMonth.isEmpty() || byMonth.contains(m))) {
                        days.add(d);
                    }
                }
            } else if (freq.equals("MONTHLY")) {
                int months = sc[1] - 1 + period * interval;
                int y = sc[0] + months / 12;
                int m = months % 12 + 1;
                if (byMonth.isEmpty() || byMonth.contains(m)) {
                    days.addAll(monthDays(y, m, sc[2]));
                }
            } else { // YEARLY
                int y = sc[0] + period * interval;
                if (byMonth.isEmpty() && byMonthDay.isEmpty() && !byDay.isEmpty()) {
                    // e.g. FREQ=YEARLY;BYDAY=20MO: weekdays counted through the year
                    int first = epochDay(y, 1, 1);
                    int last = epochDay(y, 12, 31);
                    for (int[] bd : byDay) {
                        List<Integer> all = new ArrayList<Integer>();
                        for (int d = first + mod(bd[1] - weekday(first), 7); d <= last; d += 7) {
                            all.add(d);
                        }
                        pick(days, all, bd[0]);
                    }
                } else {
                    List<Integer> months = new ArrayList<Integer>(byMonth);
                    if (months.isEmpty()) {
                        if (byMonthDay.isEmpty()) {
                            months.add(sc[1]);
                        } else {
                            for (int m = 1; m <= 12; m++) {
                                months.add(m);
                            }
                        }
                    }
                    for (int m : months) {
                        days.addAll(monthDays(y, m, sc[2]));
                    }
                }
            }
            List<Integer> list = new ArrayList<Integer>(days);
            if (bySetPos.isEmpty()) {
                return list;
            }
            TreeSet<Integer> picked = new TreeSet<Integer>();
            for (int pos : bySetPos) {
                int i = pos > 0 ? pos - 1 : list.size() + pos;
                if (i >= 0 && i < list.size()) {
                    picked.add(list.get(i));
                }
            }
            return new ArrayList<Integer>(picked);
        }

        /** First day of the period-th period, to know when to stop. */
        int periodFirstDay(int start, int period) {
            int[] sc = civil(start);
            if (freq.equals("DAILY")) {
                return start + period * interval;
            } else if (freq.equals("WEEKLY")) {
                return start - mod(weekday(start) - weekStart, 7) + period * interval * 7;
            } else if (freq.equals("MONTHLY")) {
                int months = sc[1] - 1 + period * interval;
                return epochDay(sc[0] + months / 12, months % 12 + 1, 1);
            }
            return epochDay(sc[0] + period * interval, 1, 1);
        }

        private static void pick(Set<Integer> out, List<Integer> all, int ord) {
            if (ord == 0) {
                out.addAll(all);
            } else {
                int i = ord > 0 ? ord - 1 : all.size() + ord;
                if (i >= 0 && i < all.size()) {
                    out.add(all.get(i));
                }
            }
        }
    }

    /** Start days of an event's occurrences between fromDay and toDay. */
    static List<Integer> occurrences(Event ev, Rule r, int fromDay, int toDay) {
        List<Integer> out = new ArrayList<Integer>();
        int start = ev.start.day();
        if (start >= fromDay && start <= toDay) {
            out.add(start); // DTSTART always counts as the first occurrence
        }
        int n = 1;
        long untilMs = Long.MAX_VALUE;
        int untilDay = Integer.MAX_VALUE;
        if (r.until != null) {
            untilDay = r.until.day();
            untilMs = r.until.dateOnly ? r.until.msOn(untilDay + 1) - 1 : r.until.ms();
        }
        outer:
        for (int p = 0; p < 200000; p++) {
            int first = r.periodFirstDay(start, p);
            if (first > toDay || first > untilDay) {
                break;
            }
            for (int c : r.candidates(start, p)) {
                if (c <= start) {
                    continue;
                }
                if ((r.count > 0 && n >= r.count) || c > toDay) {
                    break outer;
                }
                if (ev.start.dateOnly ? c > untilDay : ev.start.msOn(c) > untilMs) {
                    break outer;
                }
                n++;
                if (c >= fromDay) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ agenda

    /** Events from today through daysAhead days, as the frame page wants them. */
    static JSONArray upcoming(String text, int daysAhead, TimeZone local, long nowMs) throws JSONException {
        Calendar now = Calendar.getInstance(local);
        now.setTimeInMillis(nowMs);
        int today = epochDay(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH));
        int winEnd = today + daysAhead;

        List<Event> events = parse(text, local);
        Set<String> overrides = new HashSet<String>();
        for (Event e : events) {
            if (e.recurrenceId != null) {
                overrides.add(e.uid + "|" + key(e.recurrenceId, e.recurrenceId.dateOnly
                        ? e.recurrenceId.day() : 0));
            }
        }

        List<JSONObject> items = new ArrayList<JSONObject>();
        for (Event ev : events) {
            boolean allDay = ev.start.dateOnly;
            int days = 1;
            long length = 0;
            if (ev.end != null) {
                days = Math.max(1, ev.end.day() - ev.start.day());
                length = Math.max(0, ev.end.ms() - ev.start.ms());
            }
            Rule rule = null;
            if (ev.rrule != null) {
                try {
                    rule = Rule.parse(ev.rrule, local);
                } catch (RuntimeException e) {
                    rule = null;
                }
                if (rule == null) {
                    Log.w(TAG, "unsupported rule, showing first only: " + ev.rrule);
                }
            }
            List<Integer> starts;
            int from = today - days - 1;
            if (rule == null) {
                starts = new ArrayList<Integer>();
                int d = ev.start.day();
                if (d >= from && d <= winEnd + 1) {
                    starts.add(d);
                }
            } else {
                starts = occurrences(ev, rule, from, winEnd + 1);
            }
            for (int day : starts) {
                if (excluded(ev, day)) {
                    continue;
                }
                if (rule != null && overrides.contains(ev.uid + "|" + key(ev.start, day))) {
                    continue;
                }
                JSONObject item = new JSONObject();
                if (allDay) {
                    int last = day + days - 1;
                    if (last < today || day >= winEnd) {
                        continue;
                    }
                    item.put("allDay", true);
                    item.put("start", isoDay(day));
                    item.put("end", isoDay(last));
                } else {
                    long s = ev.start.msOn(day);
                    long e = s + length;
                    if (localDay(e, local) < today || localDay(s, local) >= winEnd) {
                        continue;
                    }
                    item.put("allDay", false);
                    item.put("start", localTime(s, local));
                    item.put("end", localTime(e, local));
                }
                item.put("title", ev.summary.isEmpty() ? "(no title)" : ev.summary);
                if (!ev.location.isEmpty()) {
                    item.put("location", ev.location);
                }
                items.add(item);
            }
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                String as = a.optString("start"), bs = b.optString("start");
                int c = as.substring(0, 10).compareTo(bs.substring(0, 10));
                if (c != 0) {
                    return c;
                }
                boolean aa = a.optBoolean("allDay"), ba = b.optBoolean("allDay");
                if (aa != ba) {
                    return aa ? -1 : 1;
                }
                c = as.compareTo(bs);
                return c != 0 ? c : a.optString("title").compareTo(b.optString("title"));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject o : items) {
            out.put(o);
        }
        return out;
    }

    /** Identifies one occurrence: its day for all-day events, else its instant. */
    private static String key(When w, int day) {
        return w.dateOnly ? "d" + (day != 0 ? day : w.day()) : "t" + (day != 0 ? w.msOn(day) : w.ms());
    }

    private static boolean excluded(Event ev, int day) {
        for (When x : ev.exdates) {
            if (ev.start.dateOnly || x.dateOnly) {
                if (x.day() == day) {
                    return true;
                }
            } else if (x.ms() == ev.start.msOn(day)) {
                return true;
            }
        }
        return false;
    }

    private static int localDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return epochDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String localTime(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
