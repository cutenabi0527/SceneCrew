package com.ssafy.scenecrew.invite;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.invite.service.InviteCipher;
import com.ssafy.scenecrew.invite.service.InviteJoinService;
import static org.assertj.core.api.Assertions.*;

class InviteCipherTest {
    static String key(int value) { byte[] bytes=new byte[32]; Arrays.fill(bytes,(byte)value);return Base64.getEncoder().encodeToString(bytes); }
    @Test void rotationRetainsOldKeyAndReencryptsSameLinkWithFreshIv() {
        var old=new InviteCipher("k001:"+key(1),"k001");
        var next=new InviteCipher("k001:"+key(1)+",k002:"+key(2),"k002");
        UUID event=UUID.randomUUID(); String token=old.newToken(), hash=InviteJoinService.tokenHash(token);
        String first=old.encrypt(event,hash,token),second=old.encrypt(event,hash,token);
        assertThat(first).isNotEqualTo(second);
        assertThat(next.decrypt(event,hash,first)).isEqualTo(token);
        String migrated=next.encrypt(event,hash,next.decrypt(event,hash,first));
        assertThat(migrated).startsWith("v1:k002:");
        assertThat(next.decrypt(event,hash,migrated)).isEqualTo(token);
    }
    @Test void rejectsWrongKeyMissingKeyCopiedEventHashAndTamperWithoutLeakingInputs() {
        var cipher=new InviteCipher("k001:"+key(1),"k001");UUID event=UUID.randomUUID();
        String token=cipher.newToken(),hash=InviteJoinService.tokenHash(token),sealed=cipher.encrypt(event,hash,token);
        List<Runnable> failures=List.of(
                ()->new InviteCipher("k001:"+key(2),"k001").decrypt(event,hash,sealed),
                ()->new InviteCipher("k002:"+key(2),"k002").decrypt(event,hash,sealed),
                ()->cipher.decrypt(UUID.randomUUID(),hash,sealed),
                ()->cipher.decrypt(event,InviteJoinService.tokenHash("other"),sealed),
                ()->cipher.decrypt(event,hash,sealed.substring(0,sealed.length()-4)+"AAAA"));
        for(var failure:failures) assertThatThrownBy(failure::run).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining(token).hasMessageNotContaining(sealed).hasNoCause();
    }
    @Test void configurationFailsAtStartupWithoutKeyMaterialInException() {
        for(String config:List.of("", "bad", "k001:bad", "k001:"+key(1)+",k001:"+key(2)))
            assertThatThrownBy(()->new InviteCipher(config,"k001")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid invite encryption key configuration").hasNoCause();
        assertThatThrownBy(()->new InviteCipher("k001:"+key(1),"absent")).isInstanceOf(IllegalArgumentException.class);
    }
}
