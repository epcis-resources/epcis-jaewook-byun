package org.oliot.epcis.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TimeUtil {

	public static long toUnixEpoch(String date) throws ParseException {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
			return sdf.parse(date).getTime();
		} catch (ParseException e) {
			try {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
				return sdf.parse(date).getTime();
			} catch (ParseException e1) {
				throw e1;
			}
		}
	}

	public static void checkDateFormat(String date) throws ParseException {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
			sdf.parse(date).getTime();
			return;
		} catch (ParseException e) {
			try {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
				sdf.parse(date).getTime();
				return;
			} catch (ParseException e1) {
				throw e1;
			}
		}
	}

	public static String getDateTimeStamp(long timemillis) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		return sdf.format(new Date(timemillis));
	}

}
