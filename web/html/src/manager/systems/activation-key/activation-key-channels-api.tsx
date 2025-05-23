import * as React from "react";

import { DEPRECATED_unsafeEquals } from "utils/legacy";
import Network from "utils/network";

const messageMap = {
  base_not_found_or_not_authorized: t("Base channel not found or not authorized."),
  child_not_found_or_not_authorized: t("Child channel not found or not authorized."),
  invalid_channel_id: t("Invalid channel id"),
};

export type Channel = {
  id: number;
  name: string;
  custom: boolean;
  subscribable: boolean;
  recommended: boolean;
};

export type availableChannelsType = Array<{ base: Channel | null | undefined; children: Array<Channel> }>;

type ChildrenArgsProps = {
  messages: any[];
  loading: boolean;
  loadingChildren: boolean;
  availableBaseChannels: Channel[];
  availableChannels: availableChannelsType;
  fetchChildChannels: (baseId: number) => Promise<any>;
};

type ActivationKeyChannelsProps = {
  defaultBaseId: number;
  activationKeyId: number;
  currentSelectedBaseId: number;
  onNewBaseChannel: Function;
  children: (arg0: ChildrenArgsProps) => JSX.Element;
};

type ActivationKeyChannelsState = {
  messages: Array<any>;
  loading: boolean;
  loadingChildren: boolean;
  availableBaseChannels: Array<Channel>; //[base1, base2],
  availableChannels: availableChannelsType; //[{base : null, children: []}]
  fetchedData: Map<number, Array<number>>;
};

class ActivationKeyChannelsApi extends React.Component<ActivationKeyChannelsProps, ActivationKeyChannelsState> {
  constructor(props: ActivationKeyChannelsProps) {
    super(props);

    this.state = {
      messages: [],
      loading: true,
      loadingChildren: true,
      availableBaseChannels: [], //[base1, base2],
      availableChannels: [], //[{base : null, children: []}]
      fetchedData: new Map(),
    };
  }

  UNSAFE_componentWillMount() {
    this.fetchActivationKeyChannels()
      .then(this.fetchBaseChannels)
      .then(() => this.fetchChildChannels(this.props.currentSelectedBaseId));
  }

  fetchBaseChannels = () => {
    let future;
    this.setState({ loading: true });

    future = Network.get(`/rhn/manager/api/activation-keys/base-channels`)
      .then((data) => {
        this.setState({
          availableBaseChannels: Array.from(data.data).map((channel: any) => channel.base),
          loading: false,
        });
      })
      .catch(this.handleResponseError);
    return future;
  };

  fetchActivationKeyChannels = () => {
    let future: Promise<void>;
    if (this.props.activationKeyId && !DEPRECATED_unsafeEquals(this.props.activationKeyId, -1)) {
      this.setState({ loading: true });

      future = Network.get(`/rhn/manager/api/activation-keys/${this.props.activationKeyId}/channels`)
        .then((data) => {
          const currentSelectedBaseId = data.data.base ? data.data.base.id : this.props.defaultBaseId;
          const currentChildSelectedIds = data.data.children ? data.data.children.map((c) => c.id) : [];
          this.props.onNewBaseChannel({ currentSelectedBaseId, currentChildSelectedIds });
          this.setState({
            loading: false,
          });
        })
        .catch(this.handleResponseError);
    } else {
      future = new Promise(function (resolve, reject) {
        resolve();
      });
    }
    return future;
  };

  fetchChildChannels = (baseId: number) => {
    let future: Promise<void>;

    const currentObject: any = this;
    if (currentObject.state.fetchedData && currentObject.state.fetchedData.has(baseId)) {
      future = new Promise((resolve, reject) => {
        resolve(
          currentObject.setState({
            availableChannels: currentObject.state.fetchedData.get(baseId),
          })
        );
      });
    } else {
      this.setState({ loadingChildren: true });
      future = Network.get(`/rhn/manager/api/activation-keys/base-channels/${baseId}/child-channels`)
        .then((data) => {
          this.setState({
            availableChannels: data.data,
            fetchedData: this.state.fetchedData.set(baseId, data.data),
            loadingChildren: false,
          });
        })
        .catch(this.handleResponseError);
    }
    return future;
  };

  handleResponseError = (jqXHR: JQueryXHR, arg = {}) => {
    const msg = Network.responseErrorMessage(jqXHR, (status, msg) =>
      messageMap[msg] ? t(messageMap[msg], arg) : null
    );
    this.setState((prevState) => ({
      messages: prevState.messages.concat(msg),
    }));
  };

  render() {
    return this.props.children({
      messages: this.state.messages,
      loading: this.state.loading,
      loadingChildren: this.state.loadingChildren,
      availableBaseChannels: this.state.availableBaseChannels,
      availableChannels: this.state.availableChannels,
      fetchChildChannels: this.fetchChildChannels,
    });
  }
}

export default ActivationKeyChannelsApi;
