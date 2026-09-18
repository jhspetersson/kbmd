package dev.kbmd.android;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The phone's own calendars: reading their events into the Calendar tab, and handing kbmd events over to them. */
final class PhoneCalendar {

    private PhoneCalendar() {
    }

    /** Event instances between two dates (inclusive) from every visible calendar, as a JSON array. */
    static String events(Context context, String from, String to) {
        JSONArray out = new JSONArray();
        ZoneId zone = ZoneId.systemDefault();
        long begin;
        long end;
        try {
            begin = LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli();
            end = LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        } catch (RuntimeException e) {
            return "[]";
        }
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, begin);
        ContentUris.appendId(uri, end);
        String[] columns = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.EVENT_ID};
        try (Cursor cursor = context.getContentResolver().query(uri.build(), columns,
                CalendarContract.Instances.VISIBLE + " = 1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (cursor != null && cursor.moveToNext()) {
                boolean allDay = cursor.getInt(3) != 0;
                long start = cursor.getLong(1);
                long stop = cursor.getLong(2);
                // all-day events are stored at UTC midnight; timed ones in the phone's zone
                ZonedDateTime startAt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(start), allDay ? ZoneOffset.UTC : zone);
                ZonedDateTime stopAt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(stop), allDay ? ZoneOffset.UTC : zone);
                LocalDate lastDay = allDay ? stopAt.toLocalDate().minusDays(1) : stopAt.toLocalDate();
                if (!allDay && stopAt.toLocalTime().equals(LocalTime.MIDNIGHT) && lastDay.isAfter(startAt.toLocalDate())) {
                    lastDay = lastDay.minusDays(1);
                }
                JSONObject event = new JSONObject()
                        .put("date", startAt.toLocalDate().toString())
                        .put("endDate", lastDay.isAfter(startAt.toLocalDate()) ? lastDay.toString() : JSONObject.NULL)
                        .put("time", allDay ? JSONObject.NULL : startAt.toLocalTime().toString().substring(0, 5))
                        .put("endTime", allDay ? JSONObject.NULL : stopAt.toLocalTime().toString().substring(0, 5))
                        .put("title", cursor.isNull(0) ? "(untitled)" : cursor.getString(0))
                        .put("calendar", cursor.isNull(4) ? "" : cursor.getString(4))
                        .put("eventId", cursor.getLong(5));
                out.put(event);
            }
        } catch (JSONException | SecurityException e) {
            return "[]";
        }
        return out.toString();
    }

    /** An intent for the calendar app's "new event" screen, filled in; recurrence carried as an RRULE. */
    static Intent insertIntent(String title, String date, String time, String endTime, String rrule) {
        LocalDate day = LocalDate.parse(date);
        Intent intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title);
        ZoneId zone = ZoneId.systemDefault();
        if (time == null || time.isEmpty()) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, day.atStartOfDay(zone).toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        } else {
            LocalDateTime start = LocalDateTime.of(day, LocalTime.parse(time));
            LocalDateTime stop = endTime == null || endTime.isEmpty() ? start.plusHours(1) : LocalDateTime.of(day, LocalTime.parse(endTime));
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, stop.atZone(zone).toInstant().toEpochMilli());
        }
        if (rrule != null && !rrule.isEmpty()) {
            intent.putExtra(CalendarContract.Events.RRULE, rrule);
        }
        return intent;
    }
}
