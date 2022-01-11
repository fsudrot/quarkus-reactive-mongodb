package com.example.reactive.utils;

import com.mongodb.MongoConfigurationException;
import com.mongodb.internal.dns.DefaultDnsResolver;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import io.vertx.mutiny.core.dns.DnsClient;
import io.vertx.mutiny.core.dns.SrvRecord;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.util.Arrays.asList;

@TargetClass(DefaultDnsResolver.class)
public class CustomDnsResolverSubstitution {
    @Substitute
    private static boolean sameParentDomain(final List<String> srvHostDomainParts, final String resolvedHostDomain) {
        List<String> resolvedHostDomainParts = asList(resolvedHostDomain.split("\\."));
        if (srvHostDomainParts.size() > resolvedHostDomainParts.size()) {
            return false;
        }
        return resolvedHostDomainParts.subList(resolvedHostDomainParts.size() - srvHostDomainParts.size(), resolvedHostDomainParts.size())
                .equals(srvHostDomainParts);
    }

    @Substitute
    public List<String> resolveHostFromSrvRecords(final String srvHost) {
        DnsClient dnsClient = DnsClientProducer.createDnsClient();

        String srvHostDomain = srvHost.substring(srvHost.indexOf('.') + 1);
        List<String> srvHostDomainParts = asList(srvHostDomain.split("\\."));
        List<String> hosts = new ArrayList<>();

        try {
            Duration timeout = ConfigProvider.getConfig().getOptionalValue("mongo.dns.lookup-timeout", Duration.class).orElse(Duration.ofSeconds(5));
            List<SrvRecord> srvRecords = dnsClient.resolveSRV("_mongodb._tcp." + srvHost).await().atMost(timeout); // Mutiny API, so we get the await construct and check that we can block the current thread.

            if (srvRecords.isEmpty()) {
                throw new MongoConfigurationException("No SRV records available for host " + "_mongodb._tcp." + srvHost);
            }
            for (SrvRecord srvRecord : srvRecords) {
                String resolvedHost = srvRecord.target().endsWith(".") ? srvRecord.target().substring(0, srvRecord.target().length() - 1) : srvRecord.target();
                String resolvedHostDomain = resolvedHost.substring(resolvedHost.indexOf('.') + 1);
                if (!sameParentDomain(srvHostDomainParts, resolvedHostDomain)) {
                    throw new MongoConfigurationException(
                            format("The SRV host name '%s'resolved to a host '%s 'that is not in a sub-domain of the SRV host.",
                                    srvHost, resolvedHost
                            ));
                }
                hosts.add(resolvedHost + ":" + srvRecord.port());
            }

            if (hosts.isEmpty()) {
                throw new MongoConfigurationException("Unable to find any SRV records for host " + srvHost);
            }
        } catch (Throwable e) {
            throw new MongoConfigurationException("Unable to look up SRV record for host " + srvHost, e);
        }
        return hosts;
    }

    @Substitute
    public String resolveAdditionalQueryParametersFromTxtRecords(final String host) {
        DnsClient dnsClient = DnsClientProducer.createDnsClient();

        List<String> txtRecordEnumeration;
        String additionalQueryParameters = "";

        try {
            Duration timeout = ConfigProvider.getConfig().getOptionalValue("mongo.dns.lookup-timeout", Duration.class).orElse(Duration.ofSeconds(5));
            txtRecordEnumeration = dnsClient.resolveTXT(host).await().atMost(timeout);

            for (String txtRecord : txtRecordEnumeration) {
                // Remove all space characters, as the DNS resolver for TXT records inserts a space character
                // between each character-string in a single TXT record.  That whitespace is spurious in
                // this context and must be removed
                additionalQueryParameters = txtRecord.replaceAll("\\s", "");

                if (txtRecordEnumeration.size() > 1) {
                    throw new MongoConfigurationException(format(
                            "Multiple TXT records found for host '%s'.  Only one is permitted",
                            host
                    ));
                }
            }
            return additionalQueryParameters;
        } catch (Throwable e) {
            throw new MongoConfigurationException("Unable to look up TXT record for host " + host, e);
        }
    }
}