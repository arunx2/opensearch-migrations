package org.opensearch.migrations.transform;

import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.transform.JsonCompositePrecondition.CompositeOperation;

import lombok.SneakyThrows;

public class JsonConditionalTransformerProvider implements IJsonTransformerProvider {

    public JsonConditionalTransformerProvider() {
    }

    @Override
    @SneakyThrows
    public IJsonTransformer createTransformer(Object jsonConfig) {
        if(jsonConfig instanceof List) {
            @SuppressWarnings("unchecked")
            var configs = (List<Object>) jsonConfig;
            if (configs.size() != 2) {
                throw new IllegalArgumentException(getConfigUsageStr());
            }
            var preconditionConfig = configs.get(0);
            var transformerConfig = configs.get(1);
            @SuppressWarnings("unchecked")
            List<IJsonPrecondition> precondition = new PreconditionLoader()
                .getTransformerFactoryFromServiceLoaderParsed((List<Object>) preconditionConfig)
                .collect(Collectors.toList());
            @SuppressWarnings("unchecked")
            List<IJsonTransformer> transformer = new TransformationLoader()
                .getTransformerFactoryFromServiceLoaderParsed((List<Object>) transformerConfig)
                .collect(Collectors.toList());

            return new JsonConditionalTransformer(
                new JsonCompositePrecondition(CompositeOperation.ALL,
                    precondition.toArray(IJsonPrecondition[]::new)),
                new JsonCompositeTransformer(transformer.toArray(IJsonTransformer[]::new)));
        }
        throw new IllegalArgumentException(getConfigUsageStr());
    }

    private String getConfigUsageStr() {
        return this.getClass().getName()
            + " expects the incoming configuration "
            + "to be a List<Object> with length 2.  "
            + "Script values should be a fully-formed inlined JsonPath queries encoded as a json value.  "
            + "All of the values within a configuration will be concatenated into one chained transformation.";
    }
}
