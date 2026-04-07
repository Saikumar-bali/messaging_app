import React, {useEffect, useState, useRef} from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  View,
  Switch,
  TextInput,
  TouchableOpacity,
  Alert,
  PermissionsAndroid,
  Platform,
  ScrollView,
  FlatList,
  RefreshControl,
  DeviceEventEmitter,
  KeyboardAvoidingView,
} from 'react-native';
import {NavigationContainer} from '@react-navigation/native';
import {createStackNavigator} from '@react-navigation/stack';
import {NativeModules} from 'react-native';

const {SmsBlockModule} = NativeModules;
const Stack = createStackNavigator();

// --- Components ---

const ConversationItem = ({address, body, date, onPress}: any) => (
  <TouchableOpacity style={styles.messageItem} onPress={onPress}>
    <View style={styles.messageHeader}>
      <Text style={styles.messageAddress}>{address}</Text>
      <Text style={styles.messageDate}>{new Date(date).toLocaleDateString()}</Text>
    </View>
    <Text style={styles.messageBody} numberOfLines={1}>{body}</Text>
  </TouchableOpacity>
);

const ChatBubble = ({body, date, type}: any) => {
  const isSent = type === 2;
  return (
    <View style={[styles.chatBubbleContainer, isSent ? styles.sentContainer : styles.receivedContainer]}>
      <View style={[styles.chatBubble, isSent ? styles.sentBubble : styles.receivedBubble]}>
        <Text style={[styles.chatBody, isSent && {color: '#fff'}]}>{body}</Text>
        <Text style={[styles.chatDate, isSent && {color: '#eee'}]}>{new Date(date).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}</Text>
      </View>
    </View>
  );
};

// --- Screens ---

const HomeScreen = ({navigation}: any) => {
  const [conversations, setConversations] = useState([]);
  const [refreshing, setRefreshing] = useState(false);
  const [isDefault, setIsDefault] = useState(true);

  useEffect(() => {
    fetchConversations();
    checkDefaultApp();

    const subscription = DeviceEventEmitter.addListener('onNewMessage', () => {
      fetchConversations();
    });
    
    navigation.setOptions({
      headerRight: () => (
        <TouchableOpacity 
          onPress={() => navigation.navigate('Settings')}
          style={{marginRight: 15, padding: 5}}
        >
          <Text style={{fontSize: 24}}>⚙️</Text>
        </TouchableOpacity>
      ),
    });

    return () => subscription.remove();
  }, []);

  const checkDefaultApp = async () => {
    const result = await SmsBlockModule.isDefaultSmsApp();
    setIsDefault(result);
  };

  const fetchConversations = async () => {
    try {
      if (Platform.OS === 'android') {
        const granted = await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.READ_SMS,
          PermissionsAndroid.PERMISSIONS.SEND_SMS,
        ]);
        if (granted[PermissionsAndroid.PERMISSIONS.READ_SMS] === PermissionsAndroid.RESULTS.GRANTED) {
          const list = await SmsBlockModule.getMessages();
          setConversations(list);
        }
      }
    } catch (error) {
      console.error(error);
    }
  };

  const onRefresh = async () => {
    setRefreshing(true);
    await fetchConversations();
    await checkDefaultApp();
    setRefreshing(false);
  };

  return (
    <SafeAreaView style={styles.container}>
      {!isDefault && (
        <View style={styles.warningBanner}>
          <Text style={styles.warningText}>App is not the default SMS app.</Text>
          <TouchableOpacity onPress={() => navigation.navigate('Settings')}>
            <Text style={styles.warningLink}>Fix Now</Text>
          </TouchableOpacity>
        </View>
      )}
      <FlatList
        data={conversations}
        keyExtractor={(item: any) => item.id}
        renderItem={({item}) => (
          <ConversationItem 
            {...item} 
            onPress={() => navigation.navigate('MessageDetail', { address: item.address })} 
          />
        )}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>No conversations yet.</Text>
          </View>
        }
      />
    </SafeAreaView>
  );
};

const MessageDetailScreen = ({route, navigation}: any) => {
  const {address} = route.params;
  const [history, setHistory] = useState([]);
  const [replyText, setReplyText] = useState('');
  const [sending, setSending] = useState(false);
  const scrollViewRef = useRef<any>();

  useEffect(() => {
    // Custom Header to look like a real chat app
    navigation.setOptions({
      headerLeft: () => (
        <TouchableOpacity 
          onPress={() => navigation.goBack()}
          style={{marginLeft: 10, paddingRight: 5, paddingVertical: 5}}
        >
          <Text style={{fontSize: 32, color: '#000', fontWeight: '300', marginTop: -5}}>←</Text>
        </TouchableOpacity>
      ),
      headerTitle: () => (
        <View style={{flexDirection: 'row', alignItems: 'center'}}>
          <View style={styles.headerProfileCircle}>
            <Text style={styles.headerProfileLetter}>
              {address.charAt(0).toUpperCase()}
            </Text>
          </View>
          <View style={{marginLeft: 10}}>
            <Text style={styles.headerTitleText} numberOfLines={1}>{address}</Text>
            <Text style={styles.headerSubtitleText}>online</Text>
          </View>
        </View>
      ),
      headerTitleAlign: 'left',
      headerTintColor: '#000',
    });

    loadHistory();

    const subscription = DeviceEventEmitter.addListener('onNewMessage', () => {
      loadHistory();
    });

    return () => subscription.remove();
  }, [address]);

  const loadHistory = async () => {
    try {
      const chatHistory = await SmsBlockModule.getChatHistory(address);
      setHistory(chatHistory);
    } catch (error) {
      console.error(error);
    }
  };

  const handleSend = async () => {
    if (!replyText.trim()) return;
    setSending(true);
    try {
      await SmsBlockModule.sendSms(address, replyText);
      setReplyText('');
      loadHistory(); // Refresh immediately
    } catch (error) {
      Alert.alert('Error', 'Failed to send message');
    } finally {
      setSending(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} style={{flex: 1}}>
        <ScrollView 
          ref={scrollViewRef}
          onContentSizeChange={() => scrollViewRef.current?.scrollToEnd({ animated: true })}
          style={{flex: 1, paddingHorizontal: 10}}
        >
          {history.map((msg: any, index: number) => (
            <ChatBubble key={index} {...msg} />
          ))}
        </ScrollView>
        
        <View style={styles.inputContainer}>
          <TextInput
            style={styles.chatInput}
            placeholder="Text Message"
            value={replyText}
            onChangeText={setReplyText}
            multiline
          />
          <TouchableOpacity 
            style={[styles.sendButton, !replyText.trim() && {opacity: 0.5}]} 
            onPress={handleSend}
            disabled={sending || !replyText.trim()}
          >
            <Text style={styles.sendButtonText}>{sending ? '...' : '➤'}</Text>
          </TouchableOpacity>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
};

const SettingsScreen = () => {
  const [isDefault, setIsDefault] = useState(false);
  const [blockingEnabled, setBlockingEnabled] = useState(false);
  const [otpOnly, setOtpOnly] = useState(false);
  const [forwardUrl, setForwardUrl] = useState('');

  useEffect(() => {
    checkStatus();
    loadSettings();
  }, []);

  const checkStatus = async () => {
    const result = await SmsBlockModule.isDefaultSmsApp();
    setIsDefault(result);
  };

  const loadSettings = async () => {
    const settings = await SmsBlockModule.getSettings();
    setBlockingEnabled(settings.blockingEnabled);
    setOtpOnly(settings.otpOnly);
    setForwardUrl(settings.forwardUrl);
  };

  const requestDefault = async () => {
    try {
      await SmsBlockModule.requestDefaultSmsApp();
      setTimeout(checkStatus, 2000);
    } catch (e) {
      Alert.alert('Error', 'Failed to request default SMS app');
    }
  };

  const toggleBlocking = (value: boolean) => {
    setBlockingEnabled(value);
    SmsBlockModule.setBlockingEnabled(value);
  };

  const toggleOtpOnly = (value: boolean) => {
    setOtpOnly(value);
    SmsBlockModule.setOtpOnly(value);
  };

  const saveUrl = () => {
    SmsBlockModule.setForwardUrl(forwardUrl);
    Alert.alert('Saved', 'Forwarding URL updated');
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={{padding: 20}}>
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>App Status</Text>
        <View style={styles.statusRow}>
          <Text>Default SMS App:</Text>
          <Text style={{color: isDefault ? 'green' : 'red', fontWeight: 'bold'}}>
            {isDefault ? ' YES' : ' NO'}
          </Text>
        </View>
        {!isDefault && (
          <TouchableOpacity style={styles.button} onPress={requestDefault}>
            <Text style={styles.buttonText}>Set as Default SMS App</Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Interception Controls</Text>
        <View style={styles.row}>
          <View>
            <Text style={styles.rowTitle}>Enable Blocking</Text>
            <Text style={styles.rowDesc}>Block & Delete incoming SMS</Text>
          </View>
          <Switch value={blockingEnabled} onValueChange={toggleBlocking} />
        </View>
        <View style={styles.row}>
          <View>
            <Text style={styles.rowTitle}>OTP Only Mode</Text>
            <Text style={styles.rowDesc}>Only block security codes</Text>
          </View>
          <Switch value={otpOnly} onValueChange={toggleOtpOnly} />
        </View>
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Forwarding (API)</Text>
        <Text style={styles.label}>Forwarding URL (POST JSON)</Text>
        <TextInput
          style={styles.input}
          placeholder="https://your-api.com/sms"
          value={forwardUrl}
          onChangeText={setForwardUrl}
          autoCapitalize="none"
        />
        <TouchableOpacity style={styles.button} onPress={saveUrl}>
          <Text style={styles.buttonText}>Save URL</Text>
        </TouchableOpacity>
      </View>

      <View style={{padding: 10}}>
        <Text style={{fontWeight: 'bold', color: '#d9534f', marginBottom: 5}}>Grouping Mode:</Text>
        <Text style={{fontSize: 12, color: '#666'}}>
          Messages are now grouped by sender. Click a sender to see full history.
        </Text>
      </View>
    </ScrollView>
  );
};

// --- App Entry ---

const App = () => {
  return (
    <NavigationContainer>
      <Stack.Navigator>
        <Stack.Screen name="Inbox" component={HomeScreen} options={{ title: 'Messages' }} />
        <Stack.Screen name="MessageDetail" component={MessageDetailScreen} />
        <Stack.Screen name="Settings" component={SettingsScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
  headerProfileCircle: {
    width: 35,
    height: 35,
    borderRadius: 17.5,
    backgroundColor: '#007AFF',
    justifyContent: 'center',
    alignItems: 'center',
  },
  headerProfileLetter: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 16,
  },
  headerTitleText: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#000',
  },
  headerSubtitleText: {
    fontSize: 11,
    color: '#25D366',
  },
  messageItem: {
    padding: 15,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  messageHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 5,
  },
  messageAddress: {
    fontWeight: 'bold',
    fontSize: 16,
    color: '#000',
  },
  messageDate: {
    fontSize: 12,
    color: '#888',
  },
  messageBody: {
    fontSize: 14,
    color: '#666',
  },
  chatBubbleContainer: {
    marginVertical: 5,
    flexDirection: 'row',
    width: '100%',
  },
  sentContainer: {
    justifyContent: 'flex-end',
  },
  receivedContainer: {
    justifyContent: 'flex-start',
  },
  chatBubble: {
    padding: 10,
    borderRadius: 18,
    maxWidth: '80%',
    elevation: 1,
  },
  sentBubble: {
    backgroundColor: '#007AFF',
    borderBottomRightRadius: 2,
  },
  receivedBubble: {
    backgroundColor: '#f0f0f0',
    borderBottomLeftRadius: 2,
  },
  chatBody: {
    fontSize: 15,
    color: '#000',
  },
  chatDate: {
    fontSize: 10,
    color: '#888',
    marginTop: 4,
    textAlign: 'right',
  },
  inputContainer: {
    flexDirection: 'row',
    padding: 10,
    backgroundColor: '#fff',
    borderTopWidth: 1,
    borderTopColor: '#eee',
    alignItems: 'center',
  },
  chatInput: {
    flex: 1,
    backgroundColor: '#f8f8f8',
    borderRadius: 25,
    paddingHorizontal: 15,
    paddingVertical: 10,
    marginRight: 10,
    borderWidth: 1,
    borderColor: '#ddd',
    fontSize: 16,
  },
  sendButton: {
    backgroundColor: '#007AFF',
    width: 45,
    height: 45,
    borderRadius: 22.5,
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendButtonText: {
    color: '#fff',
    fontSize: 20,
    fontWeight: 'bold',
  },
  section: {
    backgroundColor: '#fff',
    padding: 15,
    borderRadius: 10,
    marginBottom: 20,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 15,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  rowTitle: {
    fontWeight: '500',
  },
  rowDesc: {
    fontSize: 12,
    color: '#888',
  },
  label: {
    fontSize: 14,
    marginBottom: 5,
  },
  input: {
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 10,
    marginBottom: 10,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  buttonText: {
    color: '#fff',
    fontWeight: 'bold',
  },
  emptyContainer: {
    marginTop: 100,
    alignItems: 'center',
  },
  emptyText: {
    color: '#999',
  },
  warningBanner: {
    backgroundColor: '#fff3cd',
    padding: 10,
    flexDirection: 'row',
    justifyContent: 'center',
  },
  warningText: {
    color: '#856404',
  },
  warningLink: {
    color: '#007AFF',
    marginLeft: 10,
  },
});

export default App;
