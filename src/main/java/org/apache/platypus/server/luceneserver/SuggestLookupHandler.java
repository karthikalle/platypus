/*
 *
 *  *
 *  *  Copyright 2019 Yelp Inc.
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 *  *  either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  *
 *
 *
 */

package org.apache.platypus.server.luceneserver;

import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.apache.platypus.server.grpc.OneSuggestLookupResponse;
import org.apache.platypus.server.grpc.SuggestLookupHighlight;
import org.apache.platypus.server.grpc.SuggestLookupRequest;
import org.apache.platypus.server.grpc.SuggestLookupResponse;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SuggestLookupHandler implements Handler<SuggestLookupRequest, SuggestLookupResponse> {
    @Override
    public SuggestLookupResponse handle(IndexState indexState, SuggestLookupRequest suggestLookupRequest) throws HandlerException {
        String suggestName = suggestLookupRequest.getSuggestName();
        final Lookup lookup = indexState.suggesters.get(suggestName);
        if (lookup == null) {
            throw new RuntimeException("suggestName: this suggester (\"" + suggestName + "\") was not yet built; valid suggestNames: " + indexState.suggesters.keySet());
        }
        final String text = suggestLookupRequest.getText();
        final int count = suggestLookupRequest.getCount() == 0 ? 5 : suggestLookupRequest.getCount();
        final boolean allTermsRequired = suggestLookupRequest.getAllTermsRequired();
        final boolean highlight = suggestLookupRequest.getHighlight();

        final Set<BytesRef> contexts;
        if (!suggestLookupRequest.getContextsList().isEmpty()) {
            contexts = new HashSet<>();
            for (String each : suggestLookupRequest.getContextsList()) {
                contexts.add(new BytesRef(each));
            }
        } else {
            contexts = null;
        }

        List<Lookup.LookupResult> results;
        try {
            if (lookup instanceof AnalyzingInfixSuggester) {
                results = ((AnalyzingInfixSuggester) lookup).lookup(text, contexts, count, allTermsRequired, highlight);
            } else {
                results = lookup.lookup(text, contexts, false, count);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SuggestLookupResponse.Builder suggestLookupResponseBuilder = SuggestLookupResponse.newBuilder();
        for (Lookup.LookupResult result : results) {
            OneSuggestLookupResponse.Builder oneSuggestLookupResponseBuilder = OneSuggestLookupResponse.newBuilder();
            if (result.highlightKey != null) {
                oneSuggestLookupResponseBuilder.setSuggestLookupHighlight((SuggestLookupHighlight) result.highlightKey);
            } else {
                oneSuggestLookupResponseBuilder.setKey(result.key.toString());
            }
            oneSuggestLookupResponseBuilder.setWeight(result.value);
            if (result.payload != null) {
                oneSuggestLookupResponseBuilder.setPayload(result.payload.utf8ToString());
            }
            suggestLookupResponseBuilder.addResults(oneSuggestLookupResponseBuilder);
        }
        return suggestLookupResponseBuilder.build();
    }
}
