package custom.android.plugin;

import org.junit.Test;

import java.time.Year;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublishInfoDefaultsTest {
    @Test
    public void defaultsUseEntertechCentralMetadata() {
        PublishInfo publishInfo = new PublishInfo();

        assertEquals("cn.entertech", publishInfo.getCentralNamespace());
        assertEquals(String.valueOf(Year.now()), publishInfo.getPomInceptionYear());
        assertEquals("Entertech", publishInfo.getDeveloperId());
        assertEquals("Entertech", publishInfo.getDeveloperName());
        assertEquals("developer@entertech.cn", publishInfo.getDeveloperEmail());
        assertEquals("Entertech", publishInfo.getDeveloperOrganization());
        assertEquals("https://github.com/Entertech", publishInfo.getDeveloperOrganizationUrl());
        assertEquals("https://github.com/Entertech", publishInfo.getDeveloperUrl());
        assertFalse(publishInfo.getHasSource());
        assertTrue(publishInfo.getObfuscate());
    }

    @Test
    public void obfuscateAliasDoesNotMarkHasSourceExplicit() {
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setObfuscate(false);

        assertTrue(publishInfo.getHasSource());
        assertFalse(publishInfo.getObfuscate());
    }
}
