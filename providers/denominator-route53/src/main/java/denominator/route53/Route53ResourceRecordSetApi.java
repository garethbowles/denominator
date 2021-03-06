package denominator.route53;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.filter;
import static denominator.route53.ToDenominatorResourceRecordSet.isAlias;
import static denominator.route53.ToRoute53ResourceRecordSet.toTextFormat;

import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;

import org.jclouds.route53.Route53Api;
import org.jclouds.route53.domain.ChangeBatch;
import org.jclouds.route53.domain.HostedZone;
import org.jclouds.route53.domain.ResourceRecordSetIterable.NextRecord;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import denominator.ResourceRecordSetApi;
import denominator.model.ResourceRecordSet;

final class Route53ResourceRecordSetApi implements denominator.ResourceRecordSetApi {

    private final org.jclouds.route53.features.ResourceRecordSetApi route53RRsetApi;

    Route53ResourceRecordSetApi(org.jclouds.route53.features.ResourceRecordSetApi route53RRsetApi) {
        this.route53RRsetApi = route53RRsetApi;
    }

    /**
     * lists and lazily transforms all record sets who are not aliases into denominator format.
     */
    @Override
    public Iterator<ResourceRecordSet<?>> list() {
        Iterator<ResourceRecordSet<?>> iterator = route53RRsetApi.list().concat()
                                                                 .filter(not(isAlias()))
                                                                 .transform(ToDenominatorResourceRecordSet.INSTANCE)
                                                                 .iterator();
        return new GroupByRecordNameAndTypeIterator(iterator);
    }


    /**
     * lists and lazily transforms all record sets for a name which are not
     * aliases into denominator format.
     */
    @Override
    public Iterator<ResourceRecordSet<?>> listByName(String name) {
        Iterator<ResourceRecordSet<?>> iterator = route53RRsetApi.listAt(NextRecord.name(name))
                                                                 .filter(and(not(isAlias()), nameEqualTo(name)))
                                                                 .transform(ToDenominatorResourceRecordSet.INSTANCE)
                                                                 .iterator();
        return new GroupByRecordNameAndTypeIterator(iterator);
    }

    @Override
    public Optional<ResourceRecordSet<?>> getByNameAndType(String name, String type) {
        List<ResourceRecordSet<?>> matches = filterRoute53RRSByNameAndType(name, type).transform(
                ToDenominatorResourceRecordSet.INSTANCE).toList();
        switch (matches.size()) {
        case 0:
            return Optional.absent();
        case 1:
            return Optional.<ResourceRecordSet<?>> of(matches.get(0));
        }
        return Optional.<ResourceRecordSet<?>> of(new GroupByRecordNameAndTypeIterator(matches.iterator()).next());
    }

    /**
     * for efficiency, starts the list at the specified {@code name} and
     * {@code type}.
     */
    @SuppressWarnings("unchecked")
    FluentIterable<org.jclouds.route53.domain.ResourceRecordSet> filterRoute53RRSByNameAndType(String name, String type) {
        return route53RRsetApi.listAt(NextRecord.nameAndType(name, type)).filter(
                and(not(isAlias()), nameEqualTo(name), typeEqualTo(type)));
    }

    /**
     * creates a record set, adding the {@code rdata} values in the
     * {@code rrset}. If the record set already exists, the old copy is deleted
     * first. If the {@code ttl} is not present on a new {@code rrset}, we use
     * default ttl from the amazon console ({@code 300 seconds}).
     */
    @Override
    public void add(ResourceRecordSet<?> rrset) {
        Optional<Integer> ttlToApply = rrset.getTTL();

        ChangeBatch.Builder changes = ChangeBatch.builder();
        Builder<String> values = ImmutableList.builder();
        Optional<org.jclouds.route53.domain.ResourceRecordSet> oldRRS = filterRoute53RRSByNameAndType(rrset.getName(),
                rrset.getType()).first();
        if (oldRRS.isPresent()) {
            ttlToApply = ttlToApply.or(oldRRS.get().getTTL());
            changes.delete(oldRRS.get());
            values.addAll(oldRRS.get().getValues());
            values.addAll(filter(toTextFormat(rrset), not(in(oldRRS.get().getValues()))));
        } else {
            values.addAll(toTextFormat(rrset));
        }
        
        changes.create(org.jclouds.route53.domain.ResourceRecordSet.builder()
                        .name(rrset.getName())
                        .type(rrset.getType())
                        .ttl(ttlToApply.or(300))
                        .addAll(values.build()).build());

        route53RRsetApi.apply(changes.build());
    }

    @Override
    public void applyTTLToNameAndType(int ttl, String name, String type) {
        checkNotNull(ttl, "ttl");
        Optional<org.jclouds.route53.domain.ResourceRecordSet> existing = filterRoute53RRSByNameAndType(name, type)
                .first();
        if (!existing.isPresent())
            return;
        org.jclouds.route53.domain.ResourceRecordSet rrset = existing.get();
        if (rrset.getTTL().isPresent() && rrset.getTTL().get().intValue() == ttl)
            return;
        ChangeBatch.Builder changes = ChangeBatch.builder();
        changes.delete(rrset);
        changes.create(rrset.toBuilder().ttl(ttl).build());
        route53RRsetApi.apply(changes.build());
    }

    @Override
    public void replace(ResourceRecordSet<?> rrset) {
        ChangeBatch.Builder changes = ChangeBatch.builder();

        org.jclouds.route53.domain.ResourceRecordSet replacement = ToRoute53ResourceRecordSet.INSTANCE.apply(rrset);

        Optional<org.jclouds.route53.domain.ResourceRecordSet> oldRRS = filterRoute53RRSByNameAndType(rrset.getName(),
                rrset.getType()).first();
        if (oldRRS.isPresent()) {
            if (oldRRS.get().getTTL().equals(replacement.getTTL())
                    && oldRRS.get().getValues().equals(replacement.getValues()))
                return;
            changes.delete(oldRRS.get());
        }

        changes.create(replacement);

        route53RRsetApi.apply(changes.build());
    }

    /**
     * if the {@code rdata} is present in an existing RRSet, that RRSet is
     * either deleted, or replaced, depending on whether the parameter is the
     * only value or not.
     */
    @Override
    public void remove(ResourceRecordSet<?> rrset) {
        Optional<org.jclouds.route53.domain.ResourceRecordSet> oldRRS = filterRoute53RRSByNameAndType(rrset.getName(),
                rrset.getType()).first();
        if (!oldRRS.isPresent())
            return;

        List<String> valuesToRemove = ImmutableList.copyOf(filter(oldRRS.get().getValues(), in(toTextFormat(rrset))));
        if (valuesToRemove.size() == 0)
            return;

        List<String> valuesToRetain = ImmutableList.copyOf(filter(oldRRS.get().getValues(), not(in(valuesToRemove))));
        if (valuesToRetain.size() == 0) {
            route53RRsetApi.delete(oldRRS.get());
            return;
        }

        ChangeBatch.Builder changes = ChangeBatch.builder();
        changes.delete(oldRRS.get());
        changes.create(org.jclouds.route53.domain.ResourceRecordSet.builder()
                        .name(rrset.getName())
                        .type(rrset.getType())
                        .ttl(oldRRS.get().getTTL().get())
                        .addAll(valuesToRetain)
                        .build());
        route53RRsetApi.apply(changes.build());
    }

    @Override
    public void deleteByNameAndType(String name, String type) {
        Optional<org.jclouds.route53.domain.ResourceRecordSet> oldRRS = filterRoute53RRSByNameAndType(name, type)
                .first();
        if (!oldRRS.isPresent())
            return;
        route53RRsetApi.delete(oldRRS.get());
    }

    static final class Factory implements denominator.ResourceRecordSetApi.Factory {

        private final Route53Api api;

        @Inject
        Factory(Route53Api api) {
            this.api = api;
        }

        @Override
        public ResourceRecordSetApi create(final String zoneName) {
            Optional<HostedZone> zone = api.getHostedZoneApi().list().concat().firstMatch(zoneNameEquals(zoneName));
            checkArgument(zone.isPresent(), "zone %s not found", zoneName);
            return new Route53ResourceRecordSetApi(api.getResourceRecordSetApiForHostedZone(zone.get().getId()));
        }
    }

    /**
     * Amazon Hosted Zones are addressed by id, not by name.
     */
    private static final Predicate<HostedZone> zoneNameEquals(final String zoneName) {
        checkNotNull(zoneName, "zoneName");
        return new Predicate<HostedZone>() {
            @Override
            public boolean apply(HostedZone input) {
                return input.getName().equals(zoneName);
            }
        };
    }

    public static Predicate<org.jclouds.route53.domain.ResourceRecordSet> nameEqualTo(String name) {
        return new Route53NameEqualToPredicate(name);
    }

    private static class Route53NameEqualToPredicate implements Predicate<org.jclouds.route53.domain.ResourceRecordSet> {
        private final String name;

        private Route53NameEqualToPredicate(String name) {
            this.name = checkNotNull(name, "name");
        }

        @Override
        public boolean apply(org.jclouds.route53.domain.ResourceRecordSet input) {
            return name.equals(input.getName());
        }

        @Override
        public String toString() {
            return "NameEqualTo(" + name + ")";
        }
    }

    public static Predicate<org.jclouds.route53.domain.ResourceRecordSet> typeEqualTo(String type) {
        return new Route53TypeEqualToPredicate(type);
    }

    private static class Route53TypeEqualToPredicate implements Predicate<org.jclouds.route53.domain.ResourceRecordSet> {
        private final String type;

        private Route53TypeEqualToPredicate(String type) {
            this.type = checkNotNull(type, "type");
        }

        @Override
        public boolean apply(org.jclouds.route53.domain.ResourceRecordSet input) {
            return type.equals(input.getType());
        }

        @Override
        public String toString() {
            return "TypeEqualTo(" + type + ")";
        }
    }
}