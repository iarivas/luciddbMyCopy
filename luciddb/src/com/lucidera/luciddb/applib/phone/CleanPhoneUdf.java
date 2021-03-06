/*
// $Id$
// LucidDB is a DBMS optimized for business intelligence.
// Copyright (C) 2006-2007 LucidEra, Inc.
// Copyright (C) 2006-2007 The Eigenbase Project
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
package com.lucidera.luciddb.applib.phone;

import com.lucidera.luciddb.applib.resource.*;
import java.sql.Types;

/**
 * Format an input phone number in a specified format.
 * This method has several overloads determining what format
 * to use and what to do if a phone number cannot be formatted
 * into a specific format.
 *
 * Ported from //BB/bb713/server/SQL/cleanPhone.java
 */
public class CleanPhoneUdf
{
    /** Known format: Standard */
    public static final int STANDARD = 0;
    /** Known format: Parenthetical */
    public static final int PARENTHESIS = 1;

    /** Format masks for known formats */
    public static final String[] KNOWN_FORMATS =
    {
        "999-999-9999",
        "(999) 999-9999"
    };

    // Store format character array to reduce number of
    // object instantiations when executing method on large
    // result sets.

    /**
     * Convert a character to a digit.
     * Note that this may only be valid for English phones,
     * where an English character corresponds to a number.
     *
     * @param in the character to map to a digit
     * @return the digit
     * @exception ApplibException if not mappable
     */
    protected static char digitConvert(char in) throws ApplibException
    {
        char temp = in;	// Needed because switch mangles
        // switched on variable in
        // MS VM 2.0 Beta 2

        switch(temp)
        {
        case '0': case '1': case '2': case '3': case '4':
        case '5': case '6': case '7': case '8': case '9':
            return in;
        case 'A': case 'B': case 'C':
        case 'a': case 'b': case 'c':
            return '2';
        case 'D': case 'E': case 'F':
        case 'd': case 'e': case 'f':
            return '3';
        case 'G': case 'H': case 'I':
        case 'g': case 'h': case 'i':
            return '4';
        case 'J': case 'K': case 'L':
        case 'j': case 'k': case 'l':
            return '5';
        case 'M': case 'N': case 'O':
        case 'm': case 'n': case 'o':
            return '6';
        case 'P': case 'Q': case 'R': case 'S':
        case 'p': case 'q': case 'r': case 's':
            return '7';
        case 'T': case 'U': case 'V':
        case 't': case 'u': case 'v':
            return '8';
        case 'W': case 'X': case 'Y': case 'Z':
        case 'w': case 'x': case 'y': case 'z':
            return '9';
        default: throw ApplibResourceObject.get().CleanPhoneDigitUnmappable.ex();
        }
    }

    /**
     * Clean an incoming phone number by converting to a specified format mask.
     *
     * @param in the phone number to cleanse
     * @param mask the mask to convert into
     * @return a cleansed phone number
     * @exception ApplibException if number can't be converted to mask
     */
    private static String clean(String in, String mask)
        throws ApplibException
    {  
        // TODO: for improved performance, initialize once 
        char[] caMask = mask.toCharArray();
        int maskLen = caMask.length;
        char[] caReturn = new char[maskLen];
        ApplibResource res = ApplibResourceObject.get();

        int inPos = 0;
        for(int used=0; used<maskLen; used++) {
            // '9' in mask means get next number in input phone string
            if(caMask[used] == '9') {
                char c;
                // Loop through input string until we get a valid digit
                while(true) {
                    try {
                        // got valid digit, so break
                        c = digitConvert(in.charAt(inPos++));
                        break;
                    } catch(ApplibException e1) {
                        // not valid, keep looping
                    } catch(StringIndexOutOfBoundsException e2) {
                        // out of input characters, throw exception
                        throw res.PhoneNumberAndMaskMismatch.ex();
                    }
                }
                caReturn[used] = c;
            } else {
                // Copy format character over
                caReturn[used] = caMask[used];
            }
        } // for

        boolean tooLong = false;
        while(!tooLong) {
            try {
                char c = in.charAt(inPos++);
                // too long if this is anything but whitespace.
                if(!Character.isWhitespace(c)) {
                    tooLong = true;
                }
            } catch(IllegalArgumentException e1) {
                // not valid, keep looping
            } catch(StringIndexOutOfBoundsException e2) {
                // out of input characters, break out of loop
                break;
            }
        } // while

        if(tooLong) {
            throw res.PhoneNumberAndMaskMismatch.ex();
        }

        return new String(caReturn);
    }


    /**
     * Convert a phone number to the default parenthetical
     * format, i.e., (999) 999-9999
     *
     * @param in phone number to cleanse
     * @return phone number in (999) 999-9999 format
     */
    public static String execute(String in)
    {
        return execute(in, PARENTHESIS, false);
    }

    /**
     * Convert a phone number to one of two known formats:
     * (999) 999-9999 and 999-999-9999
     *
     * @param in phone number to cleanse
     * @param format_type format to convert to
     * @return phone number in specified format or original string if not
     * formattable
     * @deprecated
     */
    public static String execute(String in, int format_type)
    {
        return execute(in, format_type, false);
    }

    /**
     * Convert a phone number to one of two known formats, throwing an
     * exception if the format is not possible and rejection is request.
     * Valid format types map to (999) 999-9999 and 999-999-9999
     *
     * @param in phone number to cleanse
     * @param format_type format to convert to
     * @param reject if not formattable, throw exception
     * @return phone number in specified format
     * @exception ApplibException invalid phone output format
     * @deprecated
     */
    public static String execute(String in, int format_type, boolean reject)
        throws ApplibException
    {
        String format;

        try {
            format = KNOWN_FORMATS[format_type];
        } catch(ArrayIndexOutOfBoundsException e) {
            throw ApplibResourceObject.get().InvalidPhoneOutputFmt.ex();
        }
		 
        return execute(in, format, reject);
    }

    /**
     * Convert a phone number to an arbitrary format,
     * throwing an exception if the format is not possible and an exception
     * is requested.
     * Valid format types contain 9's to correspond to a digit, and any
     * other characters returned as part of the new string.
     *
     * @param in phone number to cleanse
     * @param format format to convert to
     * @param reject if not formattable, throw exception
     * @return phone number in specified format
     */
    public static String execute(
        String in, String format, boolean reject)
    {
        String ret;

        try {
            ret = clean(in, format);
        } catch(ApplibException e) {
            if(reject) {
                throw e;
            } else {
                ret = in;
            }
        }
        return ret;
    }
    
}
