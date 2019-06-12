package openfoodfacts.github.scrachx.openfood.views;

import com.google.zxing.*;
import com.journeyapps.barcodescanner.Decoder;
import com.journeyapps.barcodescanner.DecoderFactory;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public class MLDefaultDecoderFactory implements DecoderFactory {
    private Collection<BarcodeFormat> decodeFormats;

    public MLDefaultDecoderFactory(Collection<BarcodeFormat> decodeFormats) {
        this.decodeFormats = decodeFormats;
    }

    @Override
    public Decoder createDecoder(Map<DecodeHintType, ?> baseHints) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

        hints.putAll(baseHints);

        if (this.decodeFormats != null) {
            hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
        }
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        return new MLDecoder(reader);
    }

    private static class MLDecoder extends Decoder {
        public MLDecoder(Reader initial) {
            super(initial);
        }

        @Override
        public Result decode(LuminanceSource source) {
            System.err.println("FRED try scanning");
            if(source instanceof PlanarYUVLuminanceSource){

            }
            return super.decode(source);
        }

        @Override
        protected Result decode(BinaryBitmap bitmap) {
            final Result zxing = super.decode(bitmap);
            return zxing;
        }
    }
}
