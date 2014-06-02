package com.westudio.java.socket.pool;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class SockOutputStream extends FilterOutputStream {
	private static final int defaultBufferSize = 8192;
	protected final byte buf[];

	protected int count;

	public SockOutputStream(final OutputStream out) {
		this(out,defaultBufferSize);
	}

	public SockOutputStream(final OutputStream out, final int size) {
		super(out);
		if (size <= 0) {
			throw new IllegalArgumentException("Buffer size <= 0");
		}
		buf = new byte[size];
	}

	private void flushBuffer() throws IOException {
		if (count > 0) {
			out.write(buf, 0, count);
			count = 0;
		}
	}

	public void write(final byte b) throws IOException {
		buf[count++] = b;
		if (count == buf.length) {
			flushBuffer();
		}
	}

	public void write(final byte[] b) throws IOException {
		write(b, 0, b.length);
	}

	public void write(final byte b[], final int off, final int len)
			throws IOException {
		if (len >= buf.length) {
			flushBuffer();
			out.write(b, off, len);
		} else {
			if (len >= buf.length - count) {
				flushBuffer();
			}

			System.arraycopy(b, off, buf, count, len);
			count += len;
		}
	}

	public static boolean isSurrogate(final char ch) {
		return ch >= Character.MIN_SURROGATE && ch <= Character.MAX_SURROGATE;
	}

	public void flush() throws IOException {
		flushBuffer();
		out.flush();
	}
}
