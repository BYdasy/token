package org.token.service.impl;

import org.token.repository.system.NotificationTemplateRepository;
import org.token.repository.system.TranslationRepository;
import org.token.model.entity.NotificationTemplate;
import org.token.model.entity.Translation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationTemplateRendererTest {
    @Test void oldCodesAndShortAliasesResolveSameSavedTemplateWithSafeSinglePassVariables(){
        var templates=mock(NotificationTemplateRepository.class);var translations=mock(TranslationRepository.class);
        var template=new NotificationTemplate();template.setId(7L);template.setCode("NTF_OLD_LONG_CODE");template.setChannelType("INTERNAL");template.setEnabled(1);
        template.setTitleTemplate("Notice {{name}}");template.setContentTemplate("<p>{{name}} {{balance}}</p>");
        when(templates.findEnabled("NTF_OLD_LONG_CODE")).thenReturn(template);when(templates.getById(7L)).thenReturn(template);
        var renderer=new NotificationTemplateRenderer(templates,translations);
        var variables=Map.of("name","<img src=x> {{balance}}","balance","10");
        var first=renderer.render("NTF_OLD_LONG_CODE","en",variables);
        var second=renderer.render("T7","en",variables);
        assertEquals(first,second);assertEquals("<p>&lt;img src=x&gt; {{balance}} 10</p>",first.content());
        assertEquals(List.of("name","balance"),renderer.variables(template));
    }
    @Test void exactLocaleFallsBackToBaseThenEnglishAndDisabledTemplateDoesNotRender(){
        var templates=mock(NotificationTemplateRepository.class);var translations=mock(TranslationRepository.class);
        var template=new NotificationTemplate();template.setCode("A");template.setChannelType("INTERNAL");template.setEnabled(1);template.setTitleTemplate("原文");template.setContentTemplate("原文");
        when(templates.findEnabled("A")).thenReturn(template);
        when(translations.findLanguageCandidates(eq("notification.template.a.title"),eq(3),anyList())).thenReturn(List.of(translation("en","English"),translation("fr","Français")));
        when(translations.findLanguageCandidates(eq("notification.template.a.content"),eq(3),anyList())).thenReturn(List.of(translation("en","Body")));
        var renderer=new NotificationTemplateRenderer(templates,translations);var output=renderer.render("A","fr-CA",Map.of());
        assertEquals("Français",output.title());assertEquals("Body",output.content());
        verify(translations).findLanguageCandidates("notification.template.a.title",3,List.of("fr-ca","fr","en"));
        assertNull(renderer.render("DISABLED","zh",Map.of()));
    }
    @Test void previewUsesSelectedRecordAndFindsVariablesFromTheChosenLocalization(){
        var templates=mock(NotificationTemplateRepository.class);var translations=mock(TranslationRepository.class);
        var selected=new NotificationTemplate();selected.setId(12L);selected.setCode("SAVED");selected.setChannelType("TELEGRAM");selected.setEnabled(1);
        selected.setTitleTemplate("Title {{base}}");selected.setContentTemplate("Body {{base}}");
        when(translations.findLanguageCandidates(eq("notification.template.saved.title"),eq(6),anyList())).thenReturn(List.of(translation("en","Title {{name}}")));
        when(translations.findLanguageCandidates(eq("notification.template.saved.content"),eq(6),anyList())).thenReturn(List.of(translation("en","<b>{{amount}}</b> {{name}}")));
        var output=new NotificationTemplateRenderer(templates,translations).preview(selected,"en",Map.of("name","<sample>","amount","1.00"));
        assertEquals(List.of("name","amount"),output.variables());
        assertEquals("<b>1.00</b> &lt;sample&gt;",output.rendered().content());
        verifyNoInteractions(templates);
        selected.setEnabled(0);
        assertNull(new NotificationTemplateRenderer(templates,translations).preview(selected,"en",Map.of()));
    }
    private static Translation translation(String lang,String value){var row=new Translation();row.setLang(lang);row.setTvalue(value);return row;}
}
