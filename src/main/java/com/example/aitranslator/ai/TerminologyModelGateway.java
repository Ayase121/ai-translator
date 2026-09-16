package com.example.aitranslator.ai;

import java.util.List;

public interface TerminologyModelGateway {

    List<GlossaryEntry> extract(TerminologyRequest request);
}
