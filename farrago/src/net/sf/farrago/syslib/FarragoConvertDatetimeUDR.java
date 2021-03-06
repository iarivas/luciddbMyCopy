/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2006 The Eigenbase Project
// Copyright (C) 2006 SQLstream, Inc.
// Copyright (C) 2006 Dynamo BI Corporation
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package net.sf.farrago.syslib;

import java.sql.*;

import java.text.*;

import java.util.Calendar;

import net.sf.farrago.resource.*;
import net.sf.farrago.runtime.*;

import org.eigenbase.util14.*;


/**
 * Moved over from luciddb applib datetime package for general use. Date
 * conversion, based on standard Java libraries
 *
 * @author Elizabeth Lin
 * @version $Id$
 */
public abstract class FarragoConvertDatetimeUDR
{
    //~ Enums ------------------------------------------------------------------

    protected enum Type
    {
        UDR, DIRECT_DATE, DIRECT_TIME, DIRECT_TIMESTAMP;
    }

    //~ Methods ----------------------------------------------------------------

    public static Date char_to_date(String format, String dateString)
    {
        if ((format == null) || (dateString == null)) {
            return null;
        }
        return new Date(charToDateHelper(format, dateString));
    }

    public static Time char_to_time(String format, String timeString)
    {
        if ((format == null) || (timeString == null)) {
            return null;
        }
        return new Time(charToDateHelper(format, timeString));
    }

    public static Timestamp char_to_timestamp(
        String format,
        String timestampString)
    {
        if ((format == null) || (timestampString == null)) {
            return null;
        }
        return new Timestamp(charToDateHelper(format, timestampString));
    }

    public static String date_to_char(String format, Date d)
    {
        return date_to_char(format, d, false);
    }

    public static String time_to_char(String format, Time t)
    {
        return time_to_char(format, t, false);
    }

    public static String timestamp_to_char(String format, Timestamp ts)
    {
        return timestamp_to_char(format, ts, false);
    }

    protected static String date_to_char(
        String format,
        Date d,
        boolean directCall)
    {
        DateFormat df;

        if ((format == null) || (d == null)) {
            return null;
        }
        if (directCall) {
            df = getDateFormat(format, Type.DIRECT_DATE);
        } else {
            df = getDateFormat(format, Type.UDR);
        }
        return df.format(d);
    }

    protected static String time_to_char(
        String format,
        Time t,
        boolean directCall)
    {
        DateFormat df;

        if ((format == null) || (t == null)) {
            return null;
        }
        if (directCall) {
            df = getDateFormat(format, Type.DIRECT_TIME);
        } else {
            df = getDateFormat(format, Type.UDR);
        }
        return df.format(t);
    }

    protected static String timestamp_to_char(
        String format,
        Timestamp ts,
        boolean directCall)
    {
        DateFormat df;

        if ((format == null) || (ts == null)) {
            return null;
        }
        if (directCall) {
            df = getDateFormat(format, Type.DIRECT_TIMESTAMP);
        } else {
            df = getDateFormat(format, Type.UDR);
        }
        return df.format(ts);
    }

    /**
     * Converts a string to a standard Java date, expressed in milliseconds
     */
    private static long charToDateHelper(String format, String s)
    {
        DateFormat df = getDateFormat(format, Type.UDR);
        long ret;
        try {
            ret = df.parse(s).getTime();
        } catch (ParseException ex) {
            // check for format string with week and year
            if ((format.indexOf("w") != -1)
                && (format.length() <= s.length()))
            {
                return getPartialWeeks(format, s, df);
            } else {
                throw FarragoResource.instance().InvalidDateString.ex(
                    format,
                    s);
            }
        }
        return ret;
    }

    private static long getPartialWeeks(String format, String s, DateFormat df)
    {
        int year = -1;
        int week = -1;
        java.util.Date ret = null;

        if (format.length() > s.length()) {
            throw FarragoResource.instance().InvalidDateString.ex(
                format,
                s);
        }

        // parses for parial weeks
        int fptr = 0;
        for (int sptr = 0; sptr < s.length(); sptr++) {
            char c = format.charAt(fptr);
            switch (c) {
            // year
            case 'y':
                if (((fptr + 4) <= format.length())
                    && ((sptr + 4) <= s.length())
                    && (format.substring(fptr, fptr + 4).equals("yyyy")))
                {
                    // checks if next four chars are an integer
                    try {
                        year =
                            Integer.parseInt(
                                s.substring(sptr, sptr + 4));
                    } catch (NumberFormatException e) {
                        throw FarragoResource.instance().InvalidDateString.ex(
                            format,
                            s);
                    }
                } else {
                    throw FarragoResource.instance().InvalidDateString.ex(
                        format,
                        s);
                }

                // increments index past year format
                sptr += 3;
                fptr += 3;
                break;

            // week
            case 'w':
                int temp = -1;

                // checks for partial week 1
                if ((s.charAt(sptr) == '1')
                    && (sptr == fptr)
                    && (format.length() == s.length()))
                {
                    week = 1;
                }

                // checks for partial week 01
                if ((s.charAt(sptr) == '0')
                    && (sptr == fptr)
                    && (format.length() < s.length()))
                {
                    // check if next char is 1
                    try {
                        temp =
                            Integer.parseInt(
                                String.valueOf(s.charAt(sptr + 1)));
                        if (temp == 1) {
                            week = 1;
                            sptr += 1;
                        }
                    } catch (NumberFormatException e) {
                        throw FarragoResource.instance().InvalidDateString.ex(
                            format,
                            s);
                    }
                }

                // checks for partial week 53 or 54
                if ((s.charAt(sptr) == '5')
                    && (sptr == fptr)
                    && (format.length() < s.length()))
                {
                    // checks if next char is an integer
                    try {
                        temp =
                            Integer.parseInt(
                                String.valueOf(s.charAt(sptr + 1)));
                        if (temp == 3) {
                            week = 53;
                            sptr += 1;
                        } else if (temp == 4) {
                            week = 54;
                            sptr += 1;
                        } else {
                            // do nothing
                        }
                    } catch (NumberFormatException e) {
                        // week is not 53 or 54
                        throw FarragoResource.instance().InvalidDateString.ex(
                            format,
                            s);
                    }
                }
                break;
            default:

                // not week or year
                if (format.charAt(fptr) != s.charAt(sptr)) {
                    throw FarragoResource.instance().InvalidDateString.ex(
                        format,
                        s);
                }
            }

            fptr += 1;
        }

        if ((week > -1) && (year > -1)) {
            Calendar cal = df.getCalendar();
            cal.clear();
            if (week == 1) {
                cal.set(year, 0, 1);
                ret = cal.getTime();
            } else if ((week == 53) || (week == 54)) {
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.WEEK_OF_YEAR, 52);
                int checkDate = cal.get(Calendar.DAY_OF_MONTH);
                if ((week == 53) && (checkDate > 18)) {
                    // week 53 is a partial week
                    cal.add(Calendar.DAY_OF_MONTH, 7);
                    ret = cal.getTime();
                } else if ((week == 54) && (checkDate < 18)) {
                    // week 54 exists
                    cal.add(Calendar.DAY_OF_MONTH, 14);
                    ret = cal.getTime();
                } else {
                    throw FarragoResource.instance().InvalidDateString.ex(
                        format,
                        s);
                }
            } else {
                throw FarragoResource.instance().InvalidDateString.ex(
                    format,
                    s);
            }
        }
        if (ret != null) {
            return ret.getTime();
        } else {
            throw FarragoResource.instance().InvalidDateString.ex(format, s);
        }
    }

    /**
     * Gets a date formatter, caching it in the Farrago runtime context
     */
    private static DateFormat getDateFormat(String format, Type caller)
    {
        if (caller != Type.UDR) {
            DatetimeFormatHelper dfh =
                (DatetimeFormatHelper) FarragoUdrRuntime.getContext();
            if (dfh == null) {
                dfh = new DatetimeFormatHelper();
                FarragoUdrRuntime.setContext(dfh);
            }

            if (!dfh.isSet(caller)) {
                dfh.setFormat(caller, format);
            }
            return dfh.getFormat(caller);
        } else {
            SimpleDateFormat sdf =
                (SimpleDateFormat) FarragoUdrRuntime.getContext();
            if (sdf == null) {
                sdf = DateTimeUtil.newDateFormat(format);
                FarragoUdrRuntime.setContext(sdf);
            }
            return sdf;
        }
    }

    //~ Inner Classes ----------------------------------------------------------

    private static class DatetimeFormatHelper
    {
        private SimpleDateFormat datefmt;
        private SimpleDateFormat timefmt;
        private SimpleDateFormat timestampfmt;

        protected DatetimeFormatHelper()
        {
            datefmt = timefmt = timestampfmt = null;
        }

        protected void setFormat(Type type, String format)
        {
            switch (type) {
            case DIRECT_DATE:
                datefmt = DateTimeUtil.newDateFormat(format);
                break;
            case DIRECT_TIME:
                timefmt = DateTimeUtil.newDateFormat(format);
                break;
            case DIRECT_TIMESTAMP:
                timestampfmt = DateTimeUtil.newDateFormat(format);
                break;
            default:
                throw FarragoResource.instance().InvalidConvertDatetimeCaller
                .ex(type.name());
            }
        }

        protected SimpleDateFormat getFormat(Type type)
        {
            switch (type) {
            case DIRECT_DATE:
                return datefmt;
            case DIRECT_TIME:
                return timefmt;
            case DIRECT_TIMESTAMP:
                return timestampfmt;
            default:
                throw FarragoResource.instance().InvalidConvertDatetimeCaller
                .ex(type.name());
            }
        }

        protected boolean isSet(Type type)
        {
            switch (type) {
            case DIRECT_DATE:
                return (datefmt != null);
            case DIRECT_TIME:
                return (timefmt != null);
            case DIRECT_TIMESTAMP:
                return (timestampfmt != null);
            default:
                throw FarragoResource.instance().InvalidConvertDatetimeCaller
                .ex(type.name());
            }
        }
    }
}

// End FarragoConvertDatetimeUDR.java
