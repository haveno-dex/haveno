package haveno.core.offer;

import com.google.protobuf.Message;
import haveno.common.proto.ProtoUtil;
import haveno.common.proto.persistable.PersistableListAsObservable;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public final class SignedOfferList extends PersistableListAsObservable<SignedOffer> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SignedOfferList() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected SignedOfferList(Collection<SignedOffer> collection) {
        super(collection);
    }

    @Override
    public Message toProtoMessage() {
        return protobuf.PersistableEnvelope.newBuilder()
                .setSignedOfferList(protobuf.SignedOfferList.newBuilder()
                        .addAllSignedOffer(ProtoUtil.collectionToProto(getList(), protobuf.SignedOffer.class)))
                .build();
    }

    public static SignedOfferList fromProto(protobuf.SignedOfferList proto) {
        List<SignedOffer> list = proto.getSignedOfferList().stream()
                .map(signedOffer -> {
                    return SignedOffer.fromProto(signedOffer);
                })
                .collect(Collectors.toList());

        return new SignedOfferList(list);
    }

    @Override
    public String toString() {
        return "SignedOfferList{" +
                ",\n     list=" + getList() +
                "\n}";
    }
}
