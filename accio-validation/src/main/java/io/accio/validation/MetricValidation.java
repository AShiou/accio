/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.accio.validation;

import io.accio.base.AccioMDL;
import io.accio.base.client.Client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.accio.validation.EnumValueValidation.ENUM_VALUE_VALIDATION;
import static io.accio.validation.NotNullValidation.NOT_NULL;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableList;

public final class MetricValidation
{
    private MetricValidation() {}

    /**
     * Validate with all rules.
     */
    public static List<ValidationResult> validate(Client client, AccioMDL accioMDL)
    {
        List<ValidationRule> allRule = List.of(
                NOT_NULL,
                ENUM_VALUE_VALIDATION);
        return validate(client, accioMDL, allRule);
    }

    /**
     * Validate with specific rules.
     */
    public static List<ValidationResult> validate(Client client, AccioMDL accioMDL, List<ValidationRule> rules)
    {
        Validator validator = new Validator(client, accioMDL);
        for (ValidationRule rule : rules) {
            validator.register(rule);
        }
        return validator.validate();
    }

    static class Validator
    {
        private final Client dbClient;
        private final AccioMDL accioMDL;
        private final List<ValidationRule> tasks = new ArrayList<>();

        public Validator(Client client, AccioMDL accioMDL)
        {
            this.dbClient = requireNonNull(client);
            this.accioMDL = requireNonNull(accioMDL);
        }

        public Validator register(ValidationRule task)
        {
            tasks.add(task);
            return this;
        }

        public List<ValidationResult> validate()
        {
            final int timeoutInMinutes = 5;
            // TODO: parallel execute the validation rule
            return tasks.stream().flatMap(task -> task.validate(dbClient, accioMDL).stream())
                    .map(future -> {
                        try {
                            return future.get(timeoutInMinutes, TimeUnit.MINUTES);
                        }
                        catch (Exception e) {
                            // TODO: remind user which rule is failed
                            return ValidationResult.error("UnknownRule", Duration.ZERO, "Unexpected error: " + e.getMessage());
                        }
                    })
                    .collect(toUnmodifiableList());
        }
    }
}
