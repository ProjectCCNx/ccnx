/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.ccnx.ccn.impl.support;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * A slightly better log message formatter that includes milliseconds
 * and the thread ID.
 */
public class DetailedFormatter extends Formatter {
	
	private static final String DEFAULT_FORMAT = "Thread {0, number, integer}: {1, date} {1, time} ({2, number, integer} msec)";
	private String LINE_SEPARATOR = System.getProperty("line.separator");
	
	protected MessageFormat _formatter;
	protected Object _args[] = new Object[3];
	protected static final int DATE_INDEX = 1;
	
	public DetailedFormatter() {
		_args[DATE_INDEX] = new Date();
	}
	
	protected Date getDate() { return (Date)_args[DATE_INDEX]; }
	
	public synchronized String format(LogRecord record) {
		
		if (null == _formatter) {
			// Allow override
			_formatter = getMessageFormatter();
		}
		
		StringBuffer outputBuffer = new StringBuffer();
		
		_args[0] = record.getThreadID();
		getDate().setTime(record.getMillis());
		_args[2] = record.getMillis() % 1000;
		
		_formatter.format(_args, outputBuffer, null);

		outputBuffer.append(" ");
		if (null != record.getSourceClassName()) {
			outputBuffer.append(record.getSourceClassName());
		} else {
			outputBuffer.append(record.getLoggerName());
		}
		
		if (null != record.getSourceMethodName()) {
			outputBuffer.append(" ");
			outputBuffer.append(record.getSourceMethodName());
		}

		// Format the message itself.
		outputBuffer.append(LINE_SEPARATOR);
		outputBuffer.append(record.getLevel().getLocalizedName());
		outputBuffer.append(": ");
		outputBuffer.append(formatMessage(record));
		outputBuffer.append(LINE_SEPARATOR);

		// log exceptions
		if (null != record.getThrown()) {
			try {
				PrintWriter writer = new PrintWriter(new StringWriter());
				record.getThrown().printStackTrace(writer);
				writer.close();
			
				outputBuffer.append(writer.toString());
			} catch (Exception e) {
				// Do nothing
			}
		}
		
		return outputBuffer.toString();
	}
	
	/**
	 * Allow override.
	 * @return the formatter for this class
	 */
	protected MessageFormat getMessageFormatter() {
		return new MessageFormat(DEFAULT_FORMAT);
	}	
}
