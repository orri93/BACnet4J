package com.serotonin.bacnet4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.mutable.MutableObject;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.enums.MaxApduLength;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.npdu.test.TestNetwork;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyAck;
import com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyMultipleAck;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyMultipleRequest;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyRequest;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyMultipleRequest;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.ReadAccessResult;
import com.serotonin.bacnet4j.type.constructed.ReadAccessResult.Result;
import com.serotonin.bacnet4j.type.constructed.ReadAccessSpecification;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.ServicesSupported;
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import com.serotonin.bacnet4j.type.constructed.WriteAccessSpecification;
import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.enumerated.Segmentation;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.util.sero.ThreadUtils;

/**
 * Primarily this is a test of the DefaultTransport, but also tests aspects of Network and LocalDevice.
 *
 * @author Matthew
 */
public class MessagingTest {
    static final Logger LOG = LoggerFactory.getLogger(MessagingTest.class);

    @Test
    public void networkTest() throws Exception {
        final TestNetwork network1 = new TestNetwork(1, 200);
        final LocalDevice d1 = new LocalDevice(1, new DefaultTransport(network1));

        final MutableObject<RemoteDevice> o = new MutableObject<>();
        d1.getEventHandler().addListener(new DeviceEventAdapter() {
            @Override
            public void iAmReceived(final RemoteDevice d) {
                o.setValue(d);
            }
        });
        d1.initialize();

        final Address a2 = new Address(new byte[] { 2 });
        final TestNetwork network2 = new TestNetwork(a2, 200);
        final LocalDevice d2 = new LocalDevice(2, new DefaultTransport(network2));
        d2.initialize();

        d1.sendLocalBroadcast(new WhoIsRequest());

        ThreadUtils.sleep(1000);

        d1.terminate();
        d2.terminate();

        assertNotNull(o.getValue());
        assertEquals(a2, o.getValue().getAddress());
    }

    @Test
    public void readRequest() throws Exception {
        // Create the first local device.
        final LocalDevice d1 = new LocalDevice(1, new DefaultTransport(new TestNetwork(1, 200)));
        d1.initialize();

        // Create the second local device.
        final LocalDevice d2 = new LocalDevice(2,
                new DefaultTransport(new TestNetwork(new Address(new byte[] { 2 }), 200)));
        d2.addObject(createAnalogValue(0));
        d2.initialize();

        d1.sendGlobalBroadcast(d1.getIAm());
        d2.sendGlobalBroadcast(d2.getIAm());

        // Create the remote proxy for device 2.
        final RemoteDevice r2 = d1.getRemoteDevice(2).get();

        //        final RemoteDevice r2 = new RemoteDevice(2, a2);
        r2.setDeviceProperty(PropertyIdentifier.segmentationSupported, Segmentation.segmentedBoth);
        final ServicesSupported ss = new ServicesSupported();
        ss.setAll(true);
        r2.setDeviceProperty(PropertyIdentifier.protocolServicesSupported, ss);
        r2.setDeviceProperty(PropertyIdentifier.maxApduLengthAccepted, MaxApduLength.UP_TO_1476.getMaxLength());

        // Send an object list request from the first to the second.
        final List<ReadAccessSpecification> specs = new ArrayList<>();
        specs.add(
                new ReadAccessSpecification(new ObjectIdentifier(ObjectType.device, 2), PropertyIdentifier.objectList));
        final ServiceFuture future = d1.send(r2, new ReadPropertyMultipleRequest(new SequenceOf<>(specs)));
        final ReadPropertyMultipleAck ack = future.get();

        assertEquals(1, ack.getListOfReadAccessResults().getCount());
        final ReadAccessResult readResult = ack.getListOfReadAccessResults().get(1);
        assertEquals(d2.getConfiguration().getId(), readResult.getObjectIdentifier());
        assertEquals(1, readResult.getListOfResults().getCount());
        final Result result = readResult.getListOfResults().get(1);
        assertEquals(PropertyIdentifier.objectList, result.getPropertyIdentifier());
        @SuppressWarnings("unchecked")
        final SequenceOf<ObjectIdentifier> idList = (SequenceOf<ObjectIdentifier>) result.getReadResult().getDatum();
        assertEquals(2, idList.getCount());
        assertEquals(d2.getConfiguration().getId(), idList.get(1));
        assertEquals(new ObjectIdentifier(ObjectType.analogValue, 0), idList.get(2));

        // Send the same request, but with a null consumer.
        d1.send(r2, new ReadPropertyMultipleRequest(new SequenceOf<>(specs)), null);
        // Give the request a moment to complete.
        ThreadUtils.sleep(40);

        d1.terminate();
        d2.terminate();
    }

    @Test
    public void segmentedResponse() throws Exception {
        // Create the first local device.
        final LocalDevice d1 = new LocalDevice(1, new DefaultTransport(new TestNetwork(1, 200)));
        d1.initialize();

        // Create the second local device.
        final LocalDevice d2 = new LocalDevice(2, new DefaultTransport(new TestNetwork(2, 200)));
        for (int i = 0; i < 1000; i++)
            d2.addObject(createAnalogValue(i));
        d2.initialize();

        d1.sendGlobalBroadcast(d1.getIAm());
        d2.sendGlobalBroadcast(d2.getIAm());

        // Create the remote proxy for device 2.
        final RemoteDevice r2 = d1.getRemoteDevice(2).get();
        r2.setDeviceProperty(PropertyIdentifier.segmentationSupported, Segmentation.segmentedBoth);
        final ServicesSupported ss = new ServicesSupported();
        ss.setAll(true);
        r2.setDeviceProperty(PropertyIdentifier.protocolServicesSupported, ss);
        r2.setDeviceProperty(PropertyIdentifier.maxApduLengthAccepted, MaxApduLength.UP_TO_1476.getMaxLength());

        // Send an object list request from the first to the second.
        final List<ReadAccessSpecification> specs = new ArrayList<>();
        specs.add(
                new ReadAccessSpecification(new ObjectIdentifier(ObjectType.device, 2), PropertyIdentifier.objectList));
        final ServiceFuture future = d1.send(r2, new ReadPropertyMultipleRequest(new SequenceOf<>(specs)));
        final ReadPropertyMultipleAck ack = future.get();

        assertEquals(1, ack.getListOfReadAccessResults().getCount());
        final ReadAccessResult readResult = ack.getListOfReadAccessResults().get(1);
        assertEquals(d2.getConfiguration().getId(), readResult.getObjectIdentifier());
        assertEquals(1, readResult.getListOfResults().getCount());
        final Result result = readResult.getListOfResults().get(1);
        assertEquals(PropertyIdentifier.objectList, result.getPropertyIdentifier());
        @SuppressWarnings("unchecked")
        final SequenceOf<ObjectIdentifier> idList = (SequenceOf<ObjectIdentifier>) result.getReadResult().getDatum();
        assertEquals(1001, idList.getCount());
        assertEquals(d2.getConfiguration().getId(), idList.get(1));
        //        Assert.assertEquals(av0, idList.get(2));

        // Send the same request, but with a null consumer.
        d1.send(r2, new ReadPropertyMultipleRequest(new SequenceOf<>(specs)), null);
        // Give the request a moment to complete.
        ThreadUtils.sleep(200);

        d1.terminate();
        d2.terminate();
    }

    @Test
    public void writeRequest() throws Exception {
        // Create the first local device.
        final LocalDevice d1 = new LocalDevice(1, new DefaultTransport(new TestNetwork(1, 20)));
        d1.initialize();

        // Create the second local device.
        final LocalDevice d2 = new LocalDevice(2, new DefaultTransport(new TestNetwork(2, 30)));
        final ObjectIdentifier av0 = new ObjectIdentifier(ObjectType.analogValue, 0);
        d2.addObject(createAnalogValue(0));
        d2.initialize();

        d1.sendGlobalBroadcast(d1.getIAm());
        d2.sendGlobalBroadcast(d2.getIAm());

        // Create the remote proxy for device 2.
        final RemoteDevice r2 = d1.getRemoteDevice(2).get();
        r2.setDeviceProperty(PropertyIdentifier.segmentationSupported, Segmentation.segmentedBoth);
        final ServicesSupported ss = new ServicesSupported();
        ss.setAll(true);
        r2.setDeviceProperty(PropertyIdentifier.protocolServicesSupported, ss);
        r2.setDeviceProperty(PropertyIdentifier.maxApduLengthAccepted, MaxApduLength.UP_TO_1476.getMaxLength());

        // Send a write request from the first to the second.
        d1.send(r2, new WritePropertyRequest(av0, PropertyIdentifier.presentValue, null, new Real(3.14F), null));

        final ServiceFuture future = d1.send(r2, new ReadPropertyRequest(av0, PropertyIdentifier.presentValue));
        final ReadPropertyAck ack = future.get();

        assertEquals(av0, ack.getEventObjectIdentifier());
        assertEquals(null, ack.getPropertyArrayIndex());
        assertEquals(PropertyIdentifier.presentValue, ack.getPropertyIdentifier());
        assertEquals(new Real(3.14F), ack.getValue());

        // Send the same request, but with a null consumer.
        d1.send(r2, new ReadPropertyRequest(av0, PropertyIdentifier.presentValue), null);
        // Give the request a moment to complete.
        ThreadUtils.sleep(200);

        d1.terminate();
        d2.terminate();
    }

    @Test
    public void segmentedRequest() throws Exception {
        // Create the first local device.
        final LocalDevice d1 = new LocalDevice(1, new DefaultTransport(new TestNetwork(1, 20)));
        d1.initialize();

        // Create the second local device.
        final LocalDevice d2 = new LocalDevice(2, new DefaultTransport(new TestNetwork(2, 25)));
        for (int i = 0; i < 1000; i++)
            d2.addObject(createAnalogValue(i));
        d2.initialize();

        d1.sendGlobalBroadcast(d1.getIAm());
        d2.sendGlobalBroadcast(d2.getIAm());

        // Create the remote proxy for device 2.
        final RemoteDevice r2 = d1.getRemoteDevice(2).get();
        r2.setDeviceProperty(PropertyIdentifier.segmentationSupported, Segmentation.segmentedBoth);
        final ServicesSupported ss = new ServicesSupported();
        ss.setAll(true);
        r2.setDeviceProperty(PropertyIdentifier.protocolServicesSupported, ss);
        r2.setDeviceProperty(PropertyIdentifier.maxApduLengthAccepted, MaxApduLength.UP_TO_1476.getMaxLength());

        // Create a write multiple request
        final List<PropertyValue> propertyValues = new ArrayList<>();
        propertyValues.add(new PropertyValue(PropertyIdentifier.presentValue, new Real(2.28F)));
        propertyValues.add(new PropertyValue(PropertyIdentifier.units, EngineeringUnits.btus));
        final List<WriteAccessSpecification> specs = new ArrayList<>();
        for (int i = 0; i < 1000; i++)
            specs.add(new WriteAccessSpecification(new ObjectIdentifier(ObjectType.analogValue, i),
                    new SequenceOf<>(propertyValues)));

        // Send the request and wait for the response.
        d1.send(r2, new WritePropertyMultipleRequest(new SequenceOf<>(specs))).get();

        // Send the same request, but with a null consumer.
        d1.send(r2, new WritePropertyMultipleRequest(new SequenceOf<>(specs)), null);
        // Give the request a moment to complete.
        ThreadUtils.sleep(200);

        // Read one of the just-written values and verify.
        final ReadPropertyAck ack = d1.send(r2,
                new ReadPropertyRequest(new ObjectIdentifier(ObjectType.analogValue, 567), PropertyIdentifier.units))
                .get();

        assertEquals(new ObjectIdentifier(ObjectType.analogValue, 567), ack.getEventObjectIdentifier());
        assertEquals(null, ack.getPropertyArrayIndex());
        assertEquals(PropertyIdentifier.units, ack.getPropertyIdentifier());
        assertEquals(EngineeringUnits.btus, ack.getValue());

        d1.terminate();
        d2.terminate();
    }

    private static BACnetObject createAnalogValue(final int id) {
        return new BACnetObject(ObjectType.analogValue, id) //
                .writeProperty(PropertyIdentifier.presentValue, new Real(3.14F)) //
                .writeProperty(PropertyIdentifier.units, EngineeringUnits.noUnits) //
                .writeProperty(PropertyIdentifier.outOfService, new Boolean(false)) //
                .writeProperty(PropertyIdentifier.eventState, EventState.normal) //
                .writeProperty(PropertyIdentifier.statusFlags, new StatusFlags(false, false, false, false)) //
        ;
    }
}
