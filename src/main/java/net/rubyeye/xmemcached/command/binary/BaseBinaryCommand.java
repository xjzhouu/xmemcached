/**
 *Copyright [2009-2010] [dennis zhuang(killme2008@gmail.com)]
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License.
 *You may obtain a copy of the License at
 *             http://www.apache.org/licenses/LICENSE-2.0
 *Unless required by applicable law or agreed to in writing,
 *software distributed under the License is distributed on an "AS IS" BASIS,
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *either express or implied. See the License for the specific language governing permissions and limitations under the License
 */
/**
 *Copyright [2009-2010] [dennis zhuang(killme2008@gmail.com)]
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License.
 *You may obtain a copy of the License at
 *             http://www.apache.org/licenses/LICENSE-2.0
 *Unless required by applicable law or agreed to in writing,
 *software distributed under the License is distributed on an "AS IS" BASIS,
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *either express or implied. See the License for the specific language governing permissions and limitations under the License
 */
package net.rubyeye.xmemcached.command.binary;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import net.rubyeye.xmemcached.command.Command;
import net.rubyeye.xmemcached.command.CommandType;
import net.rubyeye.xmemcached.command.StoreCommand;
import net.rubyeye.xmemcached.exception.MemcachedDecodeException;
import net.rubyeye.xmemcached.exception.MemcachedServerException;
import net.rubyeye.xmemcached.exception.UnknownCommandException;
import net.rubyeye.xmemcached.impl.MemcachedTCPSession;
import net.rubyeye.xmemcached.transcoders.CachedData;
import net.rubyeye.xmemcached.transcoders.Transcoder;
import net.rubyeye.xmemcached.utils.ByteUtils;
import net.rubyeye.xmemcached.utils.OpaqueGenerater;

import com.google.code.yanf4j.buffer.IoBuffer;

/**
 * Base Binary command.
 * 
 * @author dennis
 * 
 */
public abstract class BaseBinaryCommand extends Command implements StoreCommand {
	static final short DEFAULT_VBUCKET_ID = 0;
	protected int expTime;
	protected long cas;
	protected Object value;

	protected OpCode opCode;
	protected BinaryDecodeStatus decodeStatus = BinaryDecodeStatus.NONE;
	protected int responseKeyLength, responseExtrasLength,
			responseTotalBodyLength;
	protected ResponseStatus responseStatus;
	protected int opaque;
	protected short vbucketId = DEFAULT_VBUCKET_ID;

	@SuppressWarnings("unchecked")
	public BaseBinaryCommand(String key, byte[] keyBytes, CommandType cmdType,
			CountDownLatch latch, int exp, long cas, Object value,
			boolean noreply, Transcoder transcoder) {
		super(key, keyBytes, cmdType, latch);
		this.expTime = exp;
		this.cas = cas;
		this.value = value;
		this.noreply = noreply;
		this.transcoder = transcoder;
	}

	public final int getExpTime() {
		return this.expTime;
	}

	public final void setExpTime(int exp) {
		this.expTime = exp;
	}

	public final long getCas() {
		return this.cas;
	}

	public int getOpaque() {
		return this.opaque;
	}

	public void setOpaque(int opaque) {
		this.opaque = opaque;
	}

	public final void setCas(long cas) {
		this.cas = cas;
	}

	public final Object getValue() {
		return this.value;
	}

	public final void setValue(Object value) {
		this.value = value;
	}

	@Override
	@SuppressWarnings("unchecked")
	public final Transcoder getTranscoder() {
		return this.transcoder;
	}

	@Override
	@SuppressWarnings("unchecked")
	public final void setTranscoder(Transcoder transcoder) {
		this.transcoder = transcoder;
	}

	@Override
	public boolean decode(MemcachedTCPSession session, ByteBuffer buffer) {
		while (true) {
			LABEL: switch (this.decodeStatus) {
			case NONE:
				if (buffer.remaining() < 24) {
					return false;
				} else {
					this.decodeStatus = BinaryDecodeStatus.READ_HEADER;
				}
				continue;
			case READ_HEADER:
				this.readHeader(buffer);
				continue;
			case READ_EXTRAS:
				if (this.readExtras(buffer, this.responseExtrasLength)) {
					this.decodeStatus = BinaryDecodeStatus.READ_KEY;
					continue;
				} else {
					return false;
				}
			case READ_KEY:
				if (this.readKey(buffer, this.responseKeyLength)) {
					this.decodeStatus = BinaryDecodeStatus.READ_VALUE;
					continue;
				} else {
					return false;
				}
			case READ_VALUE:
				if (this.responseStatus == null
						|| this.responseStatus == ResponseStatus.NO_ERROR) {
					if (this.readValue(buffer, this.responseTotalBodyLength,
							this.responseKeyLength, this.responseExtrasLength)) {
						this.decodeStatus = BinaryDecodeStatus.DONE;
						continue;
					} else {
						return false;
					}
				} else {
					// Ignore error message
					if (ByteUtils.stepBuffer(buffer,
							this.responseTotalBodyLength
									- this.responseKeyLength
									- this.responseExtrasLength)) {
						this.decodeStatus = BinaryDecodeStatus.DONE;
						continue;
					} else {
						return false;
					}
				}
			case DONE:
				if (this.finish()) {
					return true;
				} else {
					// Do not finish,continue to decode
					this.decodeStatus = BinaryDecodeStatus.NONE;
					break LABEL;
				}
			case IGNORE:
				buffer.reset();
				return true;
			}
		}
	}

	protected boolean finish() {
		if (this.result == null) {
			if (this.responseStatus == ResponseStatus.NO_ERROR) {
				this.setResult(Boolean.TRUE);
			} else {
				this.setResult(Boolean.FALSE);
			}
		}
		this.countDownLatch();
		return true;
	}

	protected void readHeader(ByteBuffer buffer) {
		this.markBuffer(buffer);
		this.readMagicNumber(buffer);
		if (!this.readOpCode(buffer)) {
			this.decodeStatus = BinaryDecodeStatus.IGNORE;
			return;
		}
		this.readKeyLength(buffer);
		this.readExtrasLength(buffer);
		this.readDataType(buffer);
		this.readStatus(buffer);
		this.readBodyLength(buffer);
		if (!this.readOpaque(buffer)) {
			this.decodeStatus = BinaryDecodeStatus.IGNORE;
			return;
		}
		this.decodeStatus = BinaryDecodeStatus.READ_EXTRAS;
		this.readCAS(buffer);

	}

	private void markBuffer(ByteBuffer buffer) {
		buffer.mark();
	}

	protected boolean readOpaque(ByteBuffer buffer) {
		if (this.noreply) {
			int returnOpaque = buffer.getInt();
			if (returnOpaque != this.opaque) {
				return false;
			}
		} else {
			ByteUtils.stepBuffer(buffer, 4);
		}
		return true;
	}

	protected long readCAS(ByteBuffer buffer) {
		ByteUtils.stepBuffer(buffer, 8);
		return 0;
	}

	protected boolean readKey(ByteBuffer buffer, int keyLength) {
		return ByteUtils.stepBuffer(buffer, keyLength);
	}

	protected boolean readValue(ByteBuffer buffer, int bodyLength,
			int keyLength, int extrasLength) {
		return ByteUtils.stepBuffer(buffer, bodyLength - keyLength
				- extrasLength);
	}

	protected boolean readExtras(ByteBuffer buffer, int extrasLength) {
		return ByteUtils.stepBuffer(buffer, extrasLength);
	}

	private int readBodyLength(ByteBuffer buffer) {
		this.responseTotalBodyLength = buffer.getInt();
		return this.responseTotalBodyLength;
	}

	protected void readStatus(ByteBuffer buffer) {
		this.responseStatus = ResponseStatus.parseShort(buffer.getShort());
		switch (this.responseStatus) {
		case NOT_SUPPORTED:
		case UNKNOWN_COMMAND:
			this.setException(new UnknownCommandException());
			break;
		case AUTH_REQUIRED:
		case FUTHER_AUTH_REQUIRED:
		case VALUE_TOO_BIG:
		case INVALID_ARGUMENTS:
		case INC_DEC_NON_NUM:
		case BELONGS_TO_ANOTHER_SRV:
		case AUTH_ERROR:
		case OUT_OF_MEMORY:
		case INTERNAL_ERROR:
		case BUSY:
		case TEMP_FAILURE:
			this.setException(new MemcachedServerException(this.responseStatus
					.errorMessage()));
			break;
		}

	}

	public final OpCode getOpCode() {
		return this.opCode;
	}

	public final void setOpCode(OpCode opCode) {
		this.opCode = opCode;
	}

	public final ResponseStatus getResponseStatus() {
		return this.responseStatus;
	}

	public final void setResponseStatus(ResponseStatus responseStatus) {
		this.responseStatus = responseStatus;
	}

	private int readKeyLength(ByteBuffer buffer) {
		this.responseKeyLength = buffer.getShort();
		return this.responseKeyLength;
	}

	private int readExtrasLength(ByteBuffer buffer) {
		this.responseExtrasLength = buffer.get();
		return this.responseExtrasLength;
	}

	private byte readDataType(ByteBuffer buffer) {
		return buffer.get();
	}

	protected boolean readOpCode(ByteBuffer buffer) {
		byte op = buffer.get();
		if (op != this.opCode.fieldValue()) {
			if (this.noreply) {
				return false;
			} else {
				throw new MemcachedDecodeException("Not a proper "
						+ this.opCode.name() + " response");
			}
		}
		return true;
	}

	private void readMagicNumber(ByteBuffer buffer) {
		byte magic = buffer.get();

		if (magic != RESPONSE_MAGIC_NUMBER) {
			throw new MemcachedDecodeException("Not a proper response");
		}
	}

	/**
	 * Set,add,replace protocol's extras length
	 */
	static final byte EXTRAS_LENGTH = (byte) 8;

	@Override
	@SuppressWarnings("unchecked")
	public void encode() {
		CachedData data = null;
		if (this.transcoder != null) {
			data = this.transcoder.encode(this.value);
		}
		// header+key+value+extras
		int length = 24 + this.getKeyLength() + this.getValueLength(data)
				+ this.getExtrasLength();

		this.ioBuffer = IoBuffer.allocate(length);
		this.fillHeader(data);
		this.fillExtras(data);
		this.fillKey();
		this.fillValue(data);

		this.ioBuffer.flip();

	}

	protected void fillValue(final CachedData data) {
		this.ioBuffer.put(data.getData());
	}

	protected void fillKey() {
		this.ioBuffer.put(this.keyBytes);
	}

	protected void fillExtras(final CachedData data) {
		this.ioBuffer.putInt(data.getFlag());
		this.ioBuffer.putInt(this.expTime);
	}

	private void fillHeader(final CachedData data) {
		byte[] bs = new byte[24];
		bs[0] = REQUEST_MAGIC_NUMBER;
		bs[1] = this.opCode.fieldValue();
		short keyLen = getKeyLength();
		bs[2] = ByteUtils.short1(keyLen);
		bs[3] = ByteUtils.short0(keyLen);
		bs[4] = this.getExtrasLength();
		// dataType,always zero bs[5]=0;

		bs[6] = ByteUtils.short1(this.vbucketId);
		bs[7] = ByteUtils.short0(this.vbucketId);
		// body len
		int bodyLen = this.getExtrasLength() + this.getKeyLength()
				+ this.getValueLength(data);
		bs[8] = ByteUtils.int3(bodyLen);
		bs[9] = ByteUtils.int2(bodyLen);
		bs[10] = ByteUtils.int1(bodyLen);
		bs[11] = ByteUtils.int0(bodyLen);
		// Opaque
		if (this.noreply) {
			this.opaque = OpaqueGenerater.getInstance().getNextValue();
		}
		bs[12] = ByteUtils.int3(this.opaque);
		bs[13] = ByteUtils.int2(this.opaque);
		bs[14] = ByteUtils.int1(this.opaque);
		bs[15] = ByteUtils.int0(this.opaque);
		// cas
		long casValue = getCasValue();
		bs[16] = ByteUtils.long7(casValue);
		bs[17] = ByteUtils.long6(casValue);
		bs[18] = ByteUtils.long5(casValue);
		bs[19] = ByteUtils.long4(casValue);
		bs[20] = ByteUtils.long3(casValue);
		bs[21] = ByteUtils.long2(casValue);
		bs[22] = ByteUtils.long1(casValue);
		bs[23] = ByteUtils.long0(casValue);
		this.ioBuffer.put(bs);
	}

	protected long getCasValue() {
		return 0L;
	}

	protected int getValueLength(final CachedData data) {
		return data.getData().length;
	}

	protected short getKeyLength() {
		return (short) this.keyBytes.length;
	}

	protected byte getExtrasLength() {
		return EXTRAS_LENGTH;
	}

}
